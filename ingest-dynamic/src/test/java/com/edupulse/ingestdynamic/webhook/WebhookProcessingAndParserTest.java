package com.edupulse.ingestdynamic.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import com.edupulse.ingestdynamic.webhook.WebhookRetryRepository.WebhookJob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;

class WebhookProcessingAndParserTest {

    private final WebhookPayloadParser parser = new WebhookPayloadParser();

    @Test
    void parsesCustomSingleAndMultiUrlPayloads() {
        byte[] custom = """
                {
                  "url": "https://kubernetes.io/docs/concepts/overview/what-is-kubernetes/",
                  "docId": "k8s-overview"
                }
                """.getBytes(StandardCharsets.UTF_8);

        List<WebhookTarget> targets = parser.parse(custom);
        assertThat(targets).containsExactly(new WebhookTarget(
                "https://kubernetes.io/docs/concepts/overview/what-is-kubernetes/",
                "k8s-overview"
        ));
    }

    @Test
    void parsesGitHubPushWebhookFilteringSupportedDocs() {
        byte[] push = """
                {
                  "ref": "refs/heads/main",
                  "repository": { "full_name": "kubernetes/website" },
                  "commits": [
                    {
                      "added": ["content/en/docs/concepts/rbac.md", "static/logo.png"],
                      "modified": ["content/en/docs/guide.pdf", "Makefile"]
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        List<WebhookTarget> targets = parser.parse(push);
        assertThat(targets).containsExactly(
                new WebhookTarget("https://raw.githubusercontent.com/kubernetes/website/main/content/en/docs/concepts/rbac.md", null),
                new WebhookTarget("https://raw.githubusercontent.com/kubernetes/website/main/content/en/docs/guide.pdf", null)
        );
    }

    @Test
    void rejectsMalformedJsonWebhookPayload() {
        assertThatThrownBy(() -> parser.parse("{not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void preservesSlashesInGitHubBranchesAndRejectsPathTraversalSegments() {
        byte[] slashBranchPush = """
                {
                  "ref": "refs/heads/release/v1.30",
                  "repository": { "full_name": "kubernetes/website" },
                  "commits": [
                    { "added": ["content/en/docs/rbac.md"] }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        List<WebhookTarget> targets = parser.parse(slashBranchPush);
        assertThat(targets).containsExactly(new WebhookTarget(
                "https://raw.githubusercontent.com/kubernetes/website/release/v1.30/content/en/docs/rbac.md",
                null
        ));

        byte[] traversalPush = """
                {
                  "ref": "refs/heads/main",
                  "repository": { "full_name": "kubernetes/website" },
                  "commits": [
                    { "added": ["../../other-org/other-repo/main/secret.md"] }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(traversalPush))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("Path traversal");
    }

    @Test
    void rejectsWebhookExceedingMaxTargetCount() {
        StringBuilder json = new StringBuilder("{\"urls\":[");
        for (int i = 0; i <= WebhookPayloadParser.MAX_TARGETS_PER_WEBHOOK; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"https://kubernetes.io/docs/page-").append(i).append("\"");
        }
        json.append("]}");

        assertThatThrownBy(() -> parser.parse(json.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("maximum allowed target count");
    }

    @Test
    void retriesOnIngestionConflictAndSurvivesSaturatedExecutorQueue() {
        WebhookRetryRepository repo = mock(WebhookRetryRepository.class);
        DynamicIngestService ingestService = mock(DynamicIngestService.class);

        WebhookJob conflictJob = new WebhookJob(4L, "https://kubernetes.io/busy", "busy", "PROCESSING", 0, 3, Instant.now(), null, "lease-4");
        when(repo.claimJob(4L)).thenReturn(Optional.of(conflictJob));
        when(ingestService.ingestUrl("https://kubernetes.io/busy", "busy"))
                .thenThrow(new IngestionConflictException("already in progress"));

        WebhookProcessingService directService = new WebhookProcessingService(repo, ingestService, Runnable::run);
        directService.processJob(4L);
        verify(repo).scheduleRetry(eq(4L), eq("lease-4"), eq(1), any(Instant.class), eq("already in progress"));

        // Saturated executor should NOT abort enqueueAndDispatch or throw 500.
        Executor rejectingExecutor = cmd -> {
            throw new TaskRejectedException("queue full");
        };
        when(repo.enqueue("https://kubernetes.io/a", null, 3)).thenReturn(10L);
        when(repo.enqueue("https://kubernetes.io/b", null, 3)).thenReturn(11L);

        WebhookProcessingService saturatedService = new WebhookProcessingService(repo, ingestService, rejectingExecutor);
        List<Long> ids = saturatedService.enqueueAndDispatch(List.of(
                new WebhookTarget("https://kubernetes.io/a", null),
                new WebhookTarget("https://kubernetes.io/b", null)
        ));
        assertThat(ids).containsExactly(10L, 11L);
    }

    @Test
    void processesWebhookJobSuccessRetryAndPermanentDeadLetter() {
        WebhookRetryRepository repo = mock(WebhookRetryRepository.class);
        DynamicIngestService ingestService = mock(DynamicIngestService.class);
        Executor directExecutor = Runnable::run;
        WebhookProcessingService service = new WebhookProcessingService(repo, ingestService, directExecutor);

        // 1. Success path
        WebhookJob okJob = new WebhookJob(1L, "https://kubernetes.io/ok", "ok", "PROCESSING", 0, 3, Instant.now(), null, "lease-1");
        when(repo.claimJob(1L)).thenReturn(Optional.of(okJob));
        when(ingestService.ingestUrl("https://kubernetes.io/ok", "ok")).thenReturn(new DynamicIngestResult(
                "ok", "https://kubernetes.io/ok", "abc", 1, null, 1, 2, DynamicIngestResult.STATUS_INGESTED, "ok"));

        service.processJob(1L);
        verify(repo).markCompleted(1L, "lease-1");

        // 2. Transient failure -> scheduled retry
        WebhookJob retryJob = new WebhookJob(2L, "https://kubernetes.io/flaky", null, "PROCESSING", 0, 3, Instant.now(), null, "lease-2");
        when(repo.claimJob(2L)).thenReturn(Optional.of(retryJob));
        when(ingestService.ingestUrl("https://kubernetes.io/flaky", null))
                .thenThrow(new RemoteFetchException(HttpStatus.GATEWAY_TIMEOUT, "timeout"));

        service.processJob(2L);
        verify(repo).scheduleRetry(eq(2L), eq("lease-2"), eq(1), any(Instant.class), eq("timeout"));

        // 3. Permanent SSRF block -> immediate DEAD_LETTER without retrying
        WebhookJob ssrfJob = new WebhookJob(3L, "http://169.254.169.254/latest/meta-data/", null, "PROCESSING", 0, 3, Instant.now(), null, "lease-3");
        when(repo.claimJob(3L)).thenReturn(Optional.of(ssrfJob));
        when(ingestService.ingestUrl("http://169.254.169.254/latest/meta-data/", null))
                .thenThrow(new SsrfBlockedException("Only HTTPS URLs are permitted"));

        service.processJob(3L);
        verify(repo).markDeadLetter(eq(3L), eq("lease-3"), eq(1), anyString());
    }
}
