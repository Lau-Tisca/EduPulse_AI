package com.edupulse.ingestdynamic.webhook;

import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses verified webhook JSON payloads into {@link WebhookTarget} entries.
 * Supports:
 * <ul>
 *   <li>Custom EduPulse payload: {@code {"url": "https://...", "docId": "..."}} or {@code {"urls": ["https://..."]}}</li>
 *   <li>GitHub {@code push} webhook payload: maps {@code commits[].added} and {@code commits[].modified}
 *       documentation files ({@code .md/.markdown/.pdf/.html/.htm/.txt}) to {@code raw.githubusercontent.com} URLs.</li>
 * </ul>
 */
@Component
public class WebhookPayloadParser {

    static final int MAX_TARGETS_PER_WEBHOOK = 100;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".md", ".markdown", ".pdf", ".html", ".htm", ".txt"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public List<WebhookTarget> parse(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length == 0) {
            throw new InvalidUrlException("Webhook payload must not be empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (JacksonException ex) {
            throw new InvalidUrlException("Malformed JSON webhook payload", ex);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidUrlException("Webhook payload must be a JSON object");
        }

        Map<String, WebhookTarget> deduplicated = new LinkedHashMap<>();

        // 1. Custom payload: single "url" (+ optional "docId")
        JsonNode urlNode = root.get("url");
        if (urlNode != null && urlNode.isString() && !urlNode.asString().isBlank()) {
            String url = urlNode.asString().trim();
            JsonNode docIdNode = root.get("docId");
            String docId = (docIdNode != null && docIdNode.isString() && !docIdNode.asString().isBlank())
                    ? docIdNode.asString().trim()
                    : null;
            deduplicated.put(url, new WebhookTarget(url, docId));
        }

        // 2. Custom payload: array "urls" (strings or objects {url, docId})
        JsonNode urlsNode = root.get("urls");
        if (urlsNode != null && urlsNode.isArray()) {
            for (JsonNode entry : urlsNode) {
                if (entry.isString() && !entry.asString().isBlank()) {
                    String url = entry.asString().trim();
                    deduplicated.putIfAbsent(url, new WebhookTarget(url, null));
                } else if (entry.isObject()) {
                    JsonNode entryUrl = entry.get("url");
                    if (entryUrl != null && entryUrl.isString() && !entryUrl.asString().isBlank()) {
                        String url = entryUrl.asString().trim();
                        JsonNode entryDocId = entry.get("docId");
                        String docId = (entryDocId != null && entryDocId.isString() && !entryDocId.asString().isBlank())
                                ? entryDocId.asString().trim()
                                : null;
                        deduplicated.put(url, new WebhookTarget(url, docId));
                    }
                }
            }
        }

        // 3. GitHub push webhook payload: repository.full_name + ref + commits[].added/modified
        if (deduplicated.isEmpty() && root.has("repository") && root.has("commits")) {
            parseGitHubPush(root, deduplicated);
        }

        if (deduplicated.size() > MAX_TARGETS_PER_WEBHOOK) {
            throw new InvalidUrlException(
                    "Webhook payload exceeds maximum allowed target count of " + MAX_TARGETS_PER_WEBHOOK);
        }

        return new ArrayList<>(deduplicated.values());
    }

    private void parseGitHubPush(JsonNode root, Map<String, WebhookTarget> out) {
        JsonNode repoNode = root.get("repository");
        JsonNode fullNameNode = repoNode == null ? null : repoNode.get("full_name");
        if (fullNameNode == null || !fullNameNode.isString() || fullNameNode.asString().isBlank()) {
            return;
        }
        String fullName = fullNameNode.asString().trim();
        if (!fullName.matches("[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+")) {
            throw new InvalidUrlException("Invalid GitHub repository.full_name in webhook payload");
        }

        String encodedBranch = encodeSafePathSegments(extractBranch(root), "branch");
        JsonNode commitsNode = root.get("commits");
        if (commitsNode == null || !commitsNode.isArray()) {
            return;
        }

        for (JsonNode commit : commitsNode) {
            addCommitPaths(commit.get("added"), fullName, encodedBranch, out);
            addCommitPaths(commit.get("modified"), fullName, encodedBranch, out);
        }
    }

    private void addCommitPaths(
            JsonNode pathsNode,
            String fullName,
            String encodedBranch,
            Map<String, WebhookTarget> out
    ) {
        if (pathsNode == null || !pathsNode.isArray()) {
            return;
        }
        for (JsonNode pathNode : pathsNode) {
            if (!pathNode.isString()) {
                continue;
            }
            String filePath = pathNode.asString().trim();
            if (filePath.isEmpty() || !isSupportedDocPath(filePath)) {
                continue;
            }
            String encodedPath = encodeSafePathSegments(filePath, "commit file path");
            String url = "https://raw.githubusercontent.com/" + fullName + "/" + encodedBranch + "/" + encodedPath;
            out.putIfAbsent(url, new WebhookTarget(url, null));
        }
    }

    private static String extractBranch(JsonNode root) {
        JsonNode refNode = root.get("ref");
        if (refNode != null && refNode.isString()) {
            String ref = refNode.asString().trim();
            if (ref.startsWith("refs/heads/")) {
                return ref.substring("refs/heads/".length());
            }
            if (ref.startsWith("refs/tags/")) {
                return ref.substring("refs/tags/".length());
            }
            if (!ref.isEmpty()) {
                return ref;
            }
        }
        JsonNode repoNode = root.get("repository");
        JsonNode defaultBranch = repoNode == null ? null : repoNode.get("default_branch");
        if (defaultBranch != null && defaultBranch.isString() && !defaultBranch.asString().isBlank()) {
            return defaultBranch.asString().trim();
        }
        return "main";
    }

    private static boolean isSupportedDocPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String encodeSafePathSegments(String path, String fieldLabel) {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0) {
            throw new InvalidUrlException("Invalid " + fieldLabel + " in webhook payload");
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        List<String> segments = Arrays.stream(trimmed.split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList();
        if (segments.isEmpty()) {
            throw new InvalidUrlException("Invalid " + fieldLabel + " in webhook payload");
        }
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new InvalidUrlException("Path traversal segment rejected in GitHub webhook " + fieldLabel);
            }
        }
        return segments.stream()
                .map(WebhookPayloadParser::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
