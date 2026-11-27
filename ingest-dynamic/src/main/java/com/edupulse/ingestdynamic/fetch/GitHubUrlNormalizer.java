package com.edupulse.ingestdynamic.fetch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rewrites GitHub HTML blob URLs ({@code https://github.com/{owner}/{repo}/blob/{ref}/{path}})
 * to {@code https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}} so Markdown/text docs
 * are fetched directly without scraping GitHub's React UI.
 */
@Component
public class GitHubUrlNormalizer {

    private static final Logger log = LoggerFactory.getLogger(GitHubUrlNormalizer.class);

    public String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException ex) {
            return rawUrl;
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return rawUrl;
        }
        String host = uri.getHost();
        if (host == null || !"github.com".equalsIgnoreCase(host)) {
            return rawUrl;
        }

        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isBlank()) {
            return rawUrl;
        }

        // Expected format: /{owner}/{repo}/blob/{ref}/{filePath...}
        String trimmedPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        String[] segments = trimmedPath.split("/", 5);
        if (segments.length == 5
                && !segments[0].isBlank()
                && !segments[1].isBlank()
                && "blob".equals(segments[2].toLowerCase(Locale.ROOT))
                && !segments[3].isBlank()
                && !segments[4].isBlank()) {
            String rewritten = "https://raw.githubusercontent.com/"
                    + segments[0] + "/" + segments[1] + "/" + segments[3] + "/" + segments[4];
            log.info("Rewrote GitHub blob URL to raw.githubusercontent.com: {} -> {}",
                    sanitize(rawUrl), sanitize(rewritten));
            return rewritten;
        }
        return rawUrl;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
