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
 * then neighbors in the order of their hit's rank. Every addition is charged by the tokens of the stitched text
 * it actually adds (ingestion overlap removed), so {@code totalTokens <= tokenBudget} holds exactly.
 * If the neighbor lookup fails for a document, that document's hits are returned without neighbors.
 */
@Service
public class NeighborStitchingService {

    static final int NEIGHBOR_WINDOW = 1;

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

        Budget budget = new Budget();
        List<RankedChunk> anchors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = 0;

        for (RankedChunk hit : hits.stream().sorted(Comparator.comparingInt(RankedChunk::rank)).toList()) {
            if (!seen.add(identity(hit))) {
                continue;
            }
            boolean added = isPositioned(hit)
                    ? budget.tryAdd(GroupKey.of(hit), hit.chunkIndex(), Piece.anchor(hit))
                    : budget.tryAddStandalone(Piece.anchor(hit));
            if (added) {
                anchors.add(hit);
            } else {
                dropped++;
                log.debug("Rerank hit dropped by token budget: rank={}, docId={}, chunkIndex={}",
                        hit.rank(), hit.docId(), hit.chunkIndex());
            }
        }

        Map<GroupKey, Map<Integer, NeighborChunk>> fetched = new HashMap<>();
        int failedLookups = 0;
        for (Map.Entry<GroupKey, TreeMap<Integer, Piece>> group : budget.groups.entrySet()) {
            GroupKey key = group.getKey();
            Set<Integer> wanted = new TreeSet<>();
            for (Integer index : group.getValue().keySet()) {
                for (int offset = -NEIGHBOR_WINDOW; offset <= NEIGHBOR_WINDOW; offset++) {
                    int neighbor = index + offset;
                    if (neighbor >= 0 && !group.getValue().containsKey(neighbor)) {
                        wanted.add(neighbor);
                    }
                }
            }
            try {
                fetched.put(key, repository.findByChunkIndexes(key.docId(), key.version(), wanted));
            } catch (DataAccessException ex) {
                failedLookups++;
                log.warn("Neighbor lookup failed for docId={}, version={}; using its hits without neighbors: {}",
                        key.docId(), key.version(), ex.getMessage());
            }
        }

        int addedNeighbors = 0;
        int skippedNeighbors = 0;
        for (RankedChunk anchor : anchors) {
            if (!isPositioned(anchor)) {
                continue;
            }
            GroupKey key = GroupKey.of(anchor);
            Map<Integer, NeighborChunk> candidates = fetched.getOrDefault(key, Map.of());
            for (int offset = -NEIGHBOR_WINDOW; offset <= NEIGHBOR_WINDOW; offset++) {
                int index = anchor.chunkIndex() + offset;
                NeighborChunk neighbor = candidates.get(index);
                if (offset == 0 || neighbor == null || budget.contains(key, index)) {
                    continue;
                }
                if (budget.tryAdd(key, index, Piece.neighbor(neighbor))) {
                    addedNeighbors++;
                } else {
                    skippedNeighbors++;
                }
            }
        }

        List<StitchedPassage> passages = budget.passages();
        int totalTokens = passages.stream().mapToInt(StitchedPassage::tokenCount).sum();
        boolean neighborsAvailable = failedLookups == 0;
        log.info("Neighbor stitching done: hits={}, kept={}, dropped={}, neighborsAdded={}, neighborsSkipped={}, "
                        + "passages={}, totalTokens={}, budget={}, failedLookups={}",
                hits.size(), anchors.size(), dropped, addedNeighbors, skippedNeighbors,
                passages.size(), totalTokens, tokenBudget, failedLookups);
        return new StitchedContext(passages, totalTokens, tokenBudget, dropped, neighborsAvailable);
    }

    /**
     * Selected pieces plus the exact token cost of their stitched text; additions are accepted only if the
     * re-stitched total stays within the budget.
     */
    private final class Budget {

        private final Map<GroupKey, TreeMap<Integer, Piece>> groups = new LinkedHashMap<>();
        private final Map<GroupKey, Integer> groupTokens = new HashMap<>();
        private final List<Piece> standalone = new ArrayList<>();
        private int used;

        boolean contains(GroupKey key, int index) {
            TreeMap<Integer, Piece> group = groups.get(key);
            return group != null && group.containsKey(index);
        }

        boolean tryAdd(GroupKey key, int index, Piece piece) {
            TreeMap<Integer, Piece> candidate = new TreeMap<>(groups.getOrDefault(key, new TreeMap<>()));
            candidate.put(index, piece);
            int candidateTokens = runs(candidate).stream().mapToInt(run -> count(join(run))).sum();
            int newUsed = used - groupTokens.getOrDefault(key, 0) + candidateTokens;
            if (newUsed > tokenBudget) {
                return false;
            }
            groups.put(key, candidate);
            groupTokens.put(key, candidateTokens);
            used = newUsed;
            return true;
        }

        boolean tryAddStandalone(Piece piece) {
            int tokens = count(piece.text());
            if (used + tokens > tokenBudget) {
                return false;
            }
            standalone.add(piece);
            used += tokens;
            return true;
        }

        List<StitchedPassage> passages() {
            List<StitchedPassage> passages = new ArrayList<>();
            for (Map.Entry<GroupKey, TreeMap<Integer, Piece>> group : groups.entrySet()) {
                for (List<Piece> run : runs(group.getValue())) {
                    passages.add(toPassage(group.getKey(), run));
                }
            }
            for (Piece piece : standalone) {
                RankedChunk hit = piece.anchor();
                passages.add(toPassage(new GroupKey(hit.docId(), hit.version()), List.of(piece)));
            }
            passages.sort(Comparator.comparingInt(StitchedPassage::rank));
            return List.copyOf(passages);
        }
    }

    /** Splits a group into runs of consecutive chunk indexes. */
    private static List<List<Piece>> runs(TreeMap<Integer, Piece> group) {
        List<List<Piece>> runs = new ArrayList<>();
        List<Piece> run = new ArrayList<>();
        Integer previousIndex = null;
        for (Map.Entry<Integer, Piece> entry : group.entrySet()) {
            if (previousIndex != null && entry.getKey() != previousIndex + 1) {
                runs.add(run);
                run = new ArrayList<>();
            }
            run.add(entry.getValue());
            previousIndex = entry.getKey();
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }
        return runs;
    }

    private static String join(List<Piece> run) {
        String text = "";
        for (Piece piece : run) {
            text = TextOverlap.join(text, piece.text());
        }
        return text;
    }

    private StitchedPassage toPassage(GroupKey key, List<Piece> run) {
        String text = join(run);
        TreeSet<Integer> pages = new TreeSet<>();
        List<Integer> chunkIndexes = new ArrayList<>();
        List<Integer> anchorIndexes = new ArrayList<>();
        String source = null;
        int rank = Integer.MAX_VALUE;
        Double rerankScore = null;
        Double vectorScore = null;

        for (Piece piece : run) {
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
                count(text), text);
    }

    private int count(String text) {
        return text == null || text.isEmpty() ? 0 : tokenCounter.applyAsInt(text);
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
