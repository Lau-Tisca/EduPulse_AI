package com.edupulse.ingestdynamic.fetch;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.security.SsrfGuard;
import com.edupulse.ingestdynamic.security.SsrfGuard.ValidatedTarget;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fetches remote HTML, Markdown, or PDF payloads with strict SSRF enforcement:
 * <ul>
 *   <li>Rewrites GitHub {@code /blob/} URLs to {@code raw.githubusercontent.com}.</li>
 *   <li>Pins the DNS-validated IP addresses at socket-connect time (preventing DNS rebinding TOCTOU).</li>
 *   <li>Disables automatic redirects and re-runs {@link SsrfGuard} on every redirect hop (max 5).</li>
 *   <li>Enforces an overall {@code APP_INGEST_TIMEOUT_MS} deadline across connects, redirects, and body reads,
 *       plus the streaming ceiling {@code APP_INGEST_MAX_BYTES}.</li>
 * </ul>
 */
@Component
public class RemoteContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(RemoteContentFetcher.class);
    static final int MAX_REDIRECTS = 5;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final String USER_AGENT = "EduPulse-RAG-Ingest/1.0";
    private static final String ACCEPT_HEADER = "text/html, text/markdown, text/plain, application/pdf, */*;q=0.8";

    private final IngestDynamicProperties properties;
    private final SsrfGuard ssrfGuard;
    private final GitHubUrlNormalizer gitHubUrlNormalizer;
    private final HttpTransport transport;

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
                new ApachePinnedHttpTransport(properties, ssrfGuard)
        );
    }

    public RemoteContentFetcher(
            IngestDynamicProperties properties,
            SsrfGuard ssrfGuard,
            GitHubUrlNormalizer gitHubUrlNormalizer,
            HttpClient httpClient
    ) {
        this(
                properties,
                ssrfGuard,
                gitHubUrlNormalizer,
                new JdkHttpClientTransport(httpClient)
        );
    }

    RemoteContentFetcher(
            IngestDynamicProperties properties,
            SsrfGuard ssrfGuard,
            GitHubUrlNormalizer gitHubUrlNormalizer,
            HttpTransport transport
    ) {
        this.properties = properties;
        this.ssrfGuard = ssrfGuard;
        this.gitHubUrlNormalizer = gitHubUrlNormalizer;
        this.transport = transport;
    }

    @PreDestroy
    public void shutdown() {
        try {
            transport.close();
        } catch (Exception ex) {
            log.debug("Ignoring error closing remote HTTP transport: {}", sanitize(ex.getMessage()));
        }
    }

    public FetchedContent fetch(String rawUrl) {
        String normalizedUrl = gitHubUrlNormalizer.normalize(rawUrl);
        ValidatedTarget currentTarget = ssrfGuard.validateAndResolve(normalizedUrl);

        long maxBytes = properties.ingest().maxBytes();
        long timeoutMs = properties.ingest().timeoutMs();
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            URI currentUri = currentTarget.uri();
            Duration remaining = remainingTimeout(deadlineNanos, timeoutMs, currentUri);

            TransportResponse response;
            try {
                response = transport.execute(currentUri, currentTarget.resolvedAddresses(), remaining);
            } catch (HttpTimeoutException | SocketTimeoutException ex) {
                log.warn("Remote fetch timed out after {} ms: url={}", timeoutMs, sanitize(currentUri.toString()));
                throw new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT,
                        "Remote fetch timed out after " + timeoutMs + " ms", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "Remote fetch interrupted", ex);
            } catch (IOException ex) {
                log.warn("Remote fetch I/O failure: url={}, error={}",
                        sanitize(currentUri.toString()), sanitize(ex.getMessage()));
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                        "Failed to fetch remote URL: " + ex.getMessage(), ex);
            }

            try (response) {
                int status = response.statusCode();
                if (isRedirect(status)) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                                "Exceeded maximum redirects (" + MAX_REDIRECTS + ") for URL: " + currentUri);
                    }
                    String location = response.location();
                    if (location == null || location.isBlank()) {
                        throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                                "Redirect response HTTP " + status + " missing Location header");
                    }
                    URI nextUri;
                    try {
                        nextUri = currentUri.resolve(location.trim());
                    } catch (IllegalArgumentException ex) {
                        throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                                "Malformed redirect Location header from " + currentUri, ex);
                    }
                    String normalizedRedirect = gitHubUrlNormalizer.normalize(nextUri.toString());
                    log.info("Following redirect hop {}/{}: {} -> {}",
                            redirectCount + 1, MAX_REDIRECTS,
                            sanitize(currentUri.toString()), sanitize(normalizedRedirect));
                    // Re-validate and re-resolve every redirect target through SSRF guard before connecting.
                    currentTarget = ssrfGuard.validateAndResolve(normalizedRedirect);
                    continue;
                }

                if (status < 200 || status >= 300) {
                    throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                            "Remote server returned HTTP " + status + " for " + currentUri);
                }

                long declaredLength = response.contentLength();
                if (declaredLength > maxBytes) {
                    log.warn("Rejected remote payload exceeding APP_INGEST_MAX_BYTES: url={}, contentLength={}, maxBytes={}",
                            sanitize(currentUri.toString()), declaredLength, maxBytes);
                    throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                            "Remote payload exceeds maximum allowed size of " + maxBytes + " bytes");
                }

                byte[] bytes = readBounded(response.body(), maxBytes, currentUri, deadlineNanos, timeoutMs);
                String contentType = response.contentType() == null ? "" : response.contentType();
                log.info("Fetched remote URL: url={}, contentType={}, bytes={}",
                        sanitize(currentUri.toString()), sanitize(contentType), bytes.length);
                return new FetchedContent(currentUri.toString(), contentType, bytes);
            } catch (RemoteFetchException ex) {
                throw ex;
            } catch (Exception ex) {
                if (ex instanceof RuntimeException runtimeEx) {
                    throw runtimeEx;
                }
                throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                        "Failed while processing remote response: " + ex.getMessage(), ex);
            }
        }

        throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "Redirect loop terminated unexpectedly");
    }

    private byte[] readBounded(InputStream body, long maxBytes, URI uri, long deadlineNanos, long timeoutMs) {
        if (body == null) {
            return new byte[0];
        }
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            long total = 0;
            int read;
            while (true) {
                checkDeadline(deadlineNanos, timeoutMs, uri);
                read = in.read(buffer);
                if (read == -1) {
                    break;
                }
                checkDeadline(deadlineNanos, timeoutMs, uri);
                total += read;
                if (total > maxBytes) {
                    log.warn("Aborted remote stream exceeding APP_INGEST_MAX_BYTES: url={}, maxBytes={}",
                            sanitize(uri.toString()), maxBytes);
                    throw new RemoteFetchException(HttpStatus.CONTENT_TOO_LARGE,
                            "Remote payload exceeds maximum allowed size of " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (RemoteFetchException ex) {
            throw ex;
        } catch (SocketTimeoutException | HttpTimeoutException ex) {
            log.warn("Remote body read timed out after {} ms: url={}", timeoutMs, sanitize(uri.toString()));
            throw new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT,
                    "Remote fetch timed out after " + timeoutMs + " ms", ex);
        } catch (IOException ex) {
            throw new RemoteFetchException(HttpStatus.BAD_GATEWAY,
                    "Error reading remote response body: " + ex.getMessage(), ex);
        }
    }

    private static Duration remainingTimeout(long deadlineNanos, long timeoutMs, URI uri) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            log.warn("Remote fetch deadline exceeded ({} ms): url={}", timeoutMs, sanitize(uri.toString()));
            throw new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT,
                    "Remote fetch timed out after " + timeoutMs + " ms");
        }
        return Duration.ofMillis(Math.max(1L, Duration.ofNanos(remainingNanos).toMillis()));
    }

    private static void checkDeadline(long deadlineNanos, long timeoutMs, URI uri) {
        if (System.nanoTime() > deadlineNanos) {
            log.warn("Remote fetch deadline exceeded during body read ({} ms): url={}",
                    timeoutMs, sanitize(uri.toString()));
            throw new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT,
                    "Remote fetch timed out after " + timeoutMs + " ms");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    interface HttpTransport extends AutoCloseable {
        TransportResponse execute(URI uri, List<InetAddress> pinnedAddresses, Duration timeout)
                throws IOException, InterruptedException;

        @Override
        default void close() throws Exception {
        }
    }

    record TransportResponse(
            int statusCode,
            String location,
            String contentType,
            long contentLength,
            InputStream body,
            AutoCloseable resource
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Ignore close error on discarded body
                }
            }
            if (resource != null) {
                resource.close();
            }
        }
    }

    /**
     * Production transport backed by Apache HttpClient 5 with a pinned {@link DnsResolver}
     * (preventing DNS rebinding TOCTOU) and socket-level connect/read timeouts.
     */
    static final class ApachePinnedHttpTransport implements HttpTransport {

        private record PinnedHost(String normalizedHost, InetAddress[] addresses) {
        }

        private final ThreadLocal<PinnedHost> pinnedHost = new ThreadLocal<>();
        private final CloseableHttpClient httpClient;

        ApachePinnedHttpTransport(IngestDynamicProperties properties, SsrfGuard ssrfGuard) {
            Timeout defaultTimeout = Timeout.ofMilliseconds(properties.ingest().timeoutMs());
            DnsResolver pinnedDnsResolver = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    String normalized = normalizeHost(host);
                    PinnedHost pinned = pinnedHost.get();
                    if (pinned != null && pinned.normalizedHost().equals(normalized) && pinned.addresses().length > 0) {
                        return pinned.addresses();
                    }
                    if (properties.ingest().blockPrivateNetworks()) {
                        return ssrfGuard.resolveAndValidateHost(normalized).toArray(InetAddress[]::new);
                    }
                    return InetAddress.getAllByName(host);
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            };

            PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinnedDnsResolver)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(defaultTimeout)
                            .setSocketTimeout(defaultTimeout)
                            .build())
                    .build();

            this.httpClient = HttpClients.custom()
                    .setConnectionManager(cm)
                    .disableRedirectHandling()
                    .build();
        }

        @Override
        public TransportResponse execute(URI uri, List<InetAddress> pinnedAddresses, Duration timeout)
                throws IOException {
            HttpGet request = new HttpGet(uri);
            request.setHeader("User-Agent", USER_AGENT);
            request.setHeader("Accept", ACCEPT_HEADER);
            Timeout remaining = Timeout.ofMilliseconds(Math.max(1L, timeout.toMillis()));
            request.setConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(remaining)
                    .setResponseTimeout(remaining)
                    .setRedirectsEnabled(false)
                    .build());

            if (pinnedAddresses != null && !pinnedAddresses.isEmpty()) {
                pinnedHost.set(new PinnedHost(normalizeHost(uri.getHost()), pinnedAddresses.toArray(InetAddress[]::new)));
            }
            try {
                ClassicHttpResponse response = httpClient.executeOpen(null, request, null);
                int status = response.getCode();
                String location = headerValue(response.getFirstHeader("Location"));
                String contentType = headerValue(response.getFirstHeader("Content-Type"));
                HttpEntity entity = response.getEntity();
                long contentLength = parseContentLength(response.getFirstHeader("Content-Length"), entity);
                InputStream body = entity == null ? null : entity.getContent();
                return new TransportResponse(status, location, contentType, contentLength, body, response);
            } finally {
                pinnedHost.remove();
            }
        }

        @Override
        public void close() throws IOException {
            httpClient.close();
        }

        private static String headerValue(Header header) {
            return header == null ? null : header.getValue();
        }

        private static long parseContentLength(Header header, HttpEntity entity) {
            if (header != null && header.getValue() != null) {
                try {
                    return Long.parseLong(header.getValue().trim());
                } catch (NumberFormatException ignored) {
                    // Fall back to entity length
                }
            }
            return entity == null ? -1L : entity.getContentLength();
        }
    }

    /**
     * Adapter allowing unit tests injecting a mock {@link HttpClient} to continue working unchanged.
     */
    private static final class JdkHttpClientTransport implements HttpTransport {

        private final HttpClient httpClient;

        private JdkHttpClientTransport(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public TransportResponse execute(URI uri, List<InetAddress> pinnedAddresses, Duration timeout)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT_HEADER)
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String location = response.headers().firstValue("Location").orElse(null);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new TransportResponse(response.statusCode(), location, contentType, contentLength, response.body(), null);
        }
    }
}
