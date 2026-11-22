package com.edupulse.retrievalpost.stitch;

import com.edupulse.retrievalpost.config.RetrievalPostProperties;
import com.edupulse.retrievalpost.rerank.RankedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Neighbor stitching (Story 4 — Task 2): expands each reranked hit with its adjacent chunks
 * ({@code chunk_index ± 1}, same {@code doc_id} and {@code version}), merges adjacent/overlapping chunks into
 * contiguous passages without duplicated text, and keeps the total context within {@code APP_TOKEN_BUDGET}.
 * <p>
 * Budget priority: hits in rerank order first (a hit that does not fit is dropped whole, never truncated),
 * then neighbors in the order of their hit's rank. If the neighbor lookup fails, the hits alone are returned.
 */
@Service
public class NeighborStitchingService {

    static final int NEIGHBOR_WINDOW = 1;

    /** Reserved per chunk for the separator/boundary effects of joining, so the budget is never exceeded. */
    private static final int JOIN_TOKEN_MARGIN = 1;

    private static final Logger log = LoggerFactory.getLogger(NeighborStitchingService.class);

    private final NeighborChunkRepository repository;
    private final int tokenBudget;
    private final ToIntFunction<String> tokenCounter;

    @Autowired
    public NeighborStitchingService(NeighborChunkRepository repository, RetrievalPostProperties properties) {
        this(repository, properties.tokenBudget(), new JTokkitTokenCountEstimator()::estimate);
    }

    NeighborStitchingService(NeighborChunkRepository repository, int tokenBudget, ToIntFunction<String> tokenCounter) {
        this.repository = repository;
        this.tokenBudget = tokenBudget;
        this.tokenCounter = tokenCounter;
    }

    public StitchedContext stitch(List<RankedChunk> hits) {
        if (hits == null || hits.isEmpty()) {
            return new StitchedContext(List.of(), 0, tokenBudget, 0, true);
        }

        Map<GroupKey, TreeMap<Integer, Piece>> groups = new LinkedHashMap<>();
        List<Piece> standalone = new ArrayList<>();
        List<RankedChunk> anchors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int used = 0;
        int dropped = 0;

        for (RankedChunk hit : hits.stream().sorted(Comparator.comparingInt(RankedChunk::rank)).toList()) {
            if (!seen.add(identity(hit))) {
                continue;
            }
            int cost = cost(hit.content());
            if (used + cost > tokenBudget) {
                dropped++;
                log.debug("Rerank hit dropped by token budget: rank={}, docId={}, chunkIndex={}, tokens={}",
                        hit.rank(), hit.docId(), hit.chunkIndex(), cost);
                continue;
            }
            used += cost;
            anchors.add(hit);
            Piece piece = Piece.anchor(hit);
            if (isPositioned(hit)) {
                groups.computeIfAbsent(GroupKey.of(hit), key -> new TreeMap<>()).put(hit.chunkIndex(), piece);
            } else {
                standalone.add(piece);
            }
        }

        boolean neighborsAvailable = true;
        Map<GroupKey, Map<Integer, NeighborChunk>> fetched = new HashMap<>();
        try {
            for (Map.Entry<GroupKey, TreeMap<Integer, Piece>> group : groups.entrySet()) {
                Set<Integer> wanted = new TreeSet<>();
                for (Integer index : group.getValue().keySet()) {
                    for (int offset = -NEIGHBOR_WINDOW; offset <= NEIGHBOR_WINDOW; offset++) {
                        int neighbor = index + offset;
                        if (neighbor >= 0 && !group.getValue().containsKey(neighbor)) {
                            wanted.add(neighbor);
                        }
                    }
                }
                GroupKey key = group.getKey();
                fetched.put(key, repository.findByChunkIndexes(key.docId(), key.version(), wanted));
            }
        } catch (DataAccessException ex) {
            neighborsAvailable = false;
            fetched.clear();
            log.warn("Neighbor lookup failed; using reranked hits without stitching: {}", ex.getMessage());
        }

        int addedNeighbors = 0;
        int skippedNeighbors = 0;
        for (RankedChunk anchor : anchors) {
            if (!isPositioned(anchor)) {
                continue;
            }
            GroupKey key = GroupKey.of(anchor);
            TreeMap<Integer, Piece> group = groups.get(key);
            Map<Integer, NeighborChunk> candidates = fetched.getOrDefault(key, Map.of());
            for (int offset = -NEIGHBOR_WINDOW; offset <= NEIGHBOR_WINDOW; offset++) {
                int index = anchor.chunkIndex() + offset;
                NeighborChunk neighbor = candidates.get(index);
                if (offset == 0 || neighbor == null || group.containsKey(index)) {
                    continue;
                }
                int cost = cost(neighbor.content());
                if (used + cost > tokenBudget) {
                    skippedNeighbors++;
                    continue;
                }
                used += cost;
                group.put(index, Piece.neighbor(neighbor));
                addedNeighbors++;
            }
        }

        List<StitchedPassage> passages = new ArrayList<>();
        for (Map.Entry<GroupKey, TreeMap<Integer, Piece>> group : groups.entrySet()) {
            List<Piece> run = new ArrayList<>();
            Integer previousIndex = null;
            for (Map.Entry<Integer, Piece> entry : group.getValue().entrySet()) {
                if (previousIndex != null && entry.getKey() != previousIndex + 1) {
                    passages.add(toPassage(group.getKey(), run));
                    run = new ArrayList<>();
                }
                run.add(entry.getValue());
                previousIndex = entry.getKey();
            }
            if (!run.isEmpty()) {
                passages.add(toPassage(group.getKey(), run));
            }
        }
        for (Piece piece : standalone) {
            RankedChunk hit = piece.anchor();
            passages.add(toPassage(new GroupKey(hit.docId(), hit.version()), List.of(piece)));
        }
        passages.sort(Comparator.comparingInt(StitchedPassage::rank));

        int totalTokens = passages.stream().mapToInt(StitchedPassage::tokenCount).sum();
        if (totalTokens > tokenBudget) {
            log.warn("Stitched context exceeds token budget: totalTokens={}, budget={}", totalTokens, tokenBudget);
        }
        log.info("Neighbor stitching done: hits={}, kept={}, dropped={}, neighborsAdded={}, neighborsSkipped={}, "
                        + "passages={}, totalTokens={}, budget={}, neighborsAvailable={}",
                hits.size(), anchors.size(), dropped, addedNeighbors, skippedNeighbors,
                passages.size(), totalTokens, tokenBudget, neighborsAvailable);
        return new StitchedContext(List.copyOf(passages), totalTokens, tokenBudget, dropped, neighborsAvailable);
    }

    private StitchedPassage toPassage(GroupKey key, List<Piece> run) {
        String text = "";
        TreeSet<Integer> pages = new TreeSet<>();
        List<Integer> chunkIndexes = new ArrayList<>();
        List<Integer> anchorIndexes = new ArrayList<>();
        String source = null;
        int rank = Integer.MAX_VALUE;
        Double rerankScore = null;
        Double vectorScore = null;

        for (Piece piece : run) {
            text = TextOverlap.join(text, piece.text());
            if (piece.page() != null) {
                pages.add(piece.page());
            }
            if (piece.chunkIndex() != null) {
                chunkIndexes.add(piece.chunkIndex());
            }
            if (source == null) {
                source = piece.source();
            }
            RankedChunk anchor = piece.anchor();
            if (anchor != null) {
                if (anchor.chunkIndex() != null) {
                    anchorIndexes.add(anchor.chunkIndex());
                }
                rank = Math.min(rank, anchor.rank());
                rerankScore = max(rerankScore, anchor.rerankScore());
                vectorScore = max(vectorScore, anchor.vectorScore());
            }
        }
        return new StitchedPassage(rank, key.docId(), key.version(), source, List.copyOf(pages),
                List.copyOf(chunkIndexes), List.copyOf(anchorIndexes), rerankScore, vectorScore,
                tokenCounter.applyAsInt(text), text);
    }

    private int cost(String text) {
        return tokenCounter.applyAsInt(text == null ? "" : text) + JOIN_TOKEN_MARGIN;
    }

    private static boolean isPositioned(RankedChunk hit) {
        return hit.docId() != null && hit.version() != null && hit.chunkIndex() != null;
    }

    private static String identity(RankedChunk hit) {
        return isPositioned(hit)
                ? hit.docId() + "|" + hit.version() + "|" + hit.chunkIndex()
                : Objects.toString(hit.id(), "rank-" + hit.rank());
    }

    private static Double max(Double current, Double candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    private record GroupKey(String docId, Integer version) {
        static GroupKey of(RankedChunk hit) {
            return new GroupKey(hit.docId(), hit.version());
        }
    }

    private record Piece(Integer chunkIndex, Integer page, String source, String text, RankedChunk anchor) {

        static Piece anchor(RankedChunk hit) {
            return new Piece(hit.chunkIndex(), hit.page(), hit.source(), nullToEmpty(hit.content()), hit);
        }

        static Piece neighbor(NeighborChunk chunk) {
            return new Piece(chunk.chunkIndex(), chunk.page(), chunk.source(), nullToEmpty(chunk.content()), null);
        }

        private static String nullToEmpty(String text) {
            return text == null ? "" : text;
        }
    }
}
