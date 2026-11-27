package com.edupulse.ingestdynamic.fetch;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.security.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fetches remote HTML, Markdown, or PDF payloads with strict SSRF enforcement:
 * <ul>
 *   <li>Rewrites GitHub {@code /blob/} URLs to {@code raw.githubusercontent.com}.</li>
 *   <li>Disables automatic redirects and re-runs {@link SsrfGuard} on every redirect hop (max 5).</li>
 *   <li>Enforces {@code APP_INGEST_TIMEOUT_MS} and streaming ceiling {@code APP_INGEST_MAX_BYTES}.</li>
 * </ul>
 */
@Component
public class RemoteContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(RemoteContentFetcher.class);
    static final int MAX_REDIRECTS = 5;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final String USER_AGENT = "EduPulse-RAG-Ingest/1.0";

    private final IngestDynamicProperties properties;
    private final SsrfGuard ssrfGuard;
    private final GitHubUrlNormalizer gitHubUrlNormalizer;
    private final HttpClient httpClient;

    @Autowired
    public RemoteContentFetcher(
            IngestDynamicProperties properties,
            SsrfGuard ssrfGuard,
            GitHubUrlNormalizer gitHubUrlNormalizer
    ) {
        this(
                properties,
                ssrfGuard,
                gitHubUrlNormalizer,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofMillis(properties.ingest().timeoutMs()))
                        .build()
        );
    }

    public RemoteContentFetcher(
            IngestDynamicProperties properties,
            SsrfGuard ssrfGuard,
            GitHubUrlNormalizer gitHubUrlNormalizer,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.ssrfGuard = ssrfGuard;
        this.gitHubUrlNormalizer = gitHubUrlNormalizer;
        this.httpClient = httpClient;
    }

    public FetchedContent fetch(String rawUrl) {
        String normalizedUrl = gitHubUrlNormalizer.normalize(rawUrl);
        URI currentUri = ssrfGuard.validate(normalizedUrl);

        long maxBytes = properties.ingest().maxBytes();
        Duration timeout = Duration.ofMillis(properties.ingest().timeoutMs());

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html, text/markdown, text/plain, application/pdf, */*;q=0.8")
                    .build();

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException ex) {
                log.warn("Remote fetch timed out after {} ms: url={}", timeout.toMillis(), currentUri);
                throw new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT,
                        "Remote fetch timed out after " + timeout.toMillis() + " ms", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "Remote fetch interrupted", ex);
            } catch (IOException ex) {
                log.warn("Remote fetch I/O failure: url={}, error={}", currentUri, ex.getMessage());
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                        "Failed to fetch remote URL: " + ex.getMessage(), ex);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                if (redirectCount == MAX_REDIRECTS) {
                    throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                            "Exceeded maximum redirects (" + MAX_REDIRECTS + ") for URL: " + currentUri);
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                            "Redirect response HTTP " + status + " missing Location header");
                }
                URI nextUri = currentUri.resolve(location.trim());
                log.info("Following redirect hop {}/{}: {} -> {}", redirectCount + 1, MAX_REDIRECTS, currentUri, nextUri);
                // Re-validate every redirect target through SSRF guard before connecting.
                currentUri = ssrfGuard.validate(nextUri);
                continue;
            }

            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                        "Remote server returned HTTP " + status + " for " + currentUri);
            }

            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maxBytes) {
                closeQuietly(response.body());
                log.warn("Rejected remote payload exceeding APP_INGEST_MAX_BYTES: url={}, contentLength={}, maxBytes={}",
                        currentUri, declaredLength, maxBytes);
                throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                        "Remote payload exceeds maximum allowed size of " + maxBytes + " bytes");
            }

            byte[] bytes = readBounded(response.body(), maxBytes, currentUri);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            log.info("Fetched remote URL: url={}, contentType={}, bytes={}", currentUri, contentType, bytes.length);
            return new FetchedContent(currentUri.toString(), contentType, bytes);
        }

        throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "Redirect loop terminated unexpectedly");
    }

    private byte[] readBounded(InputStream body, long maxBytes, URI uri) {
        if (body == null) {
            return new byte[0];
        }
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    log.warn("Aborted remote stream exceeding APP_INGEST_MAX_BYTES: url={}, maxBytes={}", uri, maxBytes);
                    throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                            "Remote payload exceeds maximum allowed size of " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (RemoteFetchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                    "Error reading remote response body: " + ex.getMessage(), ex);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Ignore close failure on discarded redirect/error body
            }
        }
    }
}
