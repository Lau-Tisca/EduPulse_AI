package com.edupulse.ingestdynamic.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import com.edupulse.ingestdynamic.security.SsrfGuard;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RemoteContentFetcherTest {

    private HttpClient httpClient;
    private RemoteContentFetcher fetcher;

    @BeforeEach
    void setUp() {
        IngestDynamicProperties properties = new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                new IngestDynamicProperties.Ingest(1024, 2000, true, IngestDynamicProperties.Ingest.DEFAULT_ALLOWED_DOMAINS),
                new IngestDynamicProperties.Webhook("secret")
        );
        SsrfGuard ssrfGuard = new SsrfGuard(properties, host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        httpClient = mock(HttpClient.class);
        fetcher = new RemoteContentFetcher(properties, ssrfGuard, new GitHubUrlNormalizer(), httpClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchesValidHttpsUrlAndRewritesGitHubBlob() throws Exception {
        HttpResponse<InputStream> response = mockResponse(200, "text/markdown", "# Kubernetes RBAC");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        FetchedContent content = fetcher.fetch("https://github.com/kubernetes/website/blob/main/README.md");

        assertThat(content.finalUrl()).isEqualTo("https://raw.githubusercontent.com/kubernetes/website/main/README.md");
        assertThat(content.contentType()).isEqualTo("text/markdown");
        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("# Kubernetes RBAC");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blocksRedirectToDisallowedDomainOrCloudMetadataBeforeSecondRequest() throws Exception {
        HttpResponse<InputStream> redirectResponse = mockRedirect(302, "http://169.254.169.254/latest/meta-data/");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirectResponse);

        assertThatThrownBy(() -> fetcher.fetch("https://kubernetes.io/docs/old"))
                .isInstanceOf(SsrfBlockedException.class);

        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsStreamExceedingMaxBytesWithoutContentLengthHeader() throws Exception {
        byte[] oversized = new byte[2048];
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/plain")), (k, v) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(oversized));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThatThrownBy(() -> fetcher.fetch("https://kubernetes.io/docs/big"))
                .isInstanceOf(RemoteFetchException.class)
                .extracting(ex -> ((RemoteFetchException) ex).status())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsHttpTimeoutExceptionToGatewayTimeout() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timed out"));

        assertThatThrownBy(() -> fetcher.fetch("https://kubernetes.io/docs/slow"))
                .isInstanceOf(RemoteFetchException.class)
                .extracting(ex -> ((RemoteFetchException) ex).status())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> mockResponse(int status, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of(
                        "Content-Type", List.of(contentType),
                        "Content-Length", List.of(String.valueOf(bytes.length))
                ),
                (k, v) -> true
        ));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> mockRedirect(int status, String location) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Location", List.of(location)),
                (k, v) -> true
        ));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return response;
    }
}
