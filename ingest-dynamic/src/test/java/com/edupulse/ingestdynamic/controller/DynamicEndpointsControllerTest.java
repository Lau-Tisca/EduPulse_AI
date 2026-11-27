package com.edupulse.ingestdynamic.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.dto.DocumentVersionInfo;
import com.edupulse.ingestdynamic.dto.DynamicIngestResult;
import com.edupulse.ingestdynamic.exception.GlobalExceptionHandler;
import com.edupulse.ingestdynamic.exception.IngestionConflictException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import com.edupulse.ingestdynamic.security.HmacSignatureVerifier;
import com.edupulse.ingestdynamic.service.DynamicIngestService;
import com.edupulse.ingestdynamic.webhook.WebhookPayloadParser;
import com.edupulse.ingestdynamic.webhook.WebhookProcessingService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DynamicEndpointsControllerTest {

    private static final String SECRET = "webhook-test-secret";

    private DynamicIngestService dynamicIngestService;
    private WebhookProcessingService webhookProcessingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dynamicIngestService = mock(DynamicIngestService.class);
        webhookProcessingService = mock(WebhookProcessingService.class);

        IngestDynamicProperties props = new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook(SECRET)
        );
        HmacSignatureVerifier verifier = new HmacSignatureVerifier(props);
        DynamicIngestController ingestController = new DynamicIngestController(dynamicIngestService);
        WebhookController webhookController = new WebhookController(
                verifier,
                new WebhookPayloadParser(),
                webhookProcessingService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(ingestController, webhookController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void postDynamicUrlReturns200OnSuccessAnd403OnSsrfBlock() throws Exception {
        when(dynamicIngestService.ingestUrl("https://kubernetes.io/docs/home/", "k8s-home"))
                .thenReturn(new DynamicIngestResult(
                        "k8s-home", "https://kubernetes.io/docs/home/", "abc", 1, null, 1, 4,
                        DynamicIngestResult.STATUS_INGESTED, "ok"));

        mockMvc.perform(post("/ingest/dynamic/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://kubernetes.io/docs/home/\",\"docId\":\"k8s-home\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("k8s-home"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("INGESTED"));

        when(dynamicIngestService.ingestUrl("http://169.254.169.254/latest/meta-data/", null))
                .thenThrow(new SsrfBlockedException("Only HTTPS URLs are permitted"));

        mockMvc.perform(post("/ingest/dynamic/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void postDynamicUrlReturns400WhenUrlMissing() throws Exception {
        mockMvc.perform(post("/ingest/dynamic/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void postWebhookReturns401OnInvalidOrMissingSignatureAnd202OnValidSignature() throws Exception {
        byte[] payload = "{\"url\":\"https://kubernetes.io/docs/concepts/security/rbac-good-practices/\"}"
                .getBytes(StandardCharsets.UTF_8);

        // Missing signature -> 401
        mockMvc.perform(post("/ingest/dynamic/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Invalid signature -> 401
        mockMvc.perform(post("/ingest/dynamic/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HmacSignatureVerifier.SIGNATURE_HEADER, "sha256=deadbeef")
                        .content(payload))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(webhookProcessingService);

        // Valid signature -> 202 Accepted
        String validSig = "sha256=" + HmacSignatureVerifier.computeHex(payload, SECRET);
        when(webhookProcessingService.enqueueAndDispatch(any())).thenReturn(List.of(42L));

        mockMvc.perform(post("/ingest/dynamic/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HmacSignatureVerifier.SIGNATURE_HEADER, validSig)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.queuedCount").value(1))
                .andExpect(jsonPath("$.jobIds[0]").value(42));
    }

    @Test
    void returns409OnIngestionConflict413OnOversizedWebhookAnd200OnVersionHistory() throws Exception {
        when(dynamicIngestService.ingestUrl("https://kubernetes.io/docs/busy", "k8s-busy"))
                .thenThrow(new IngestionConflictException("Ingestion for document 'k8s-busy' is already in progress"));

        mockMvc.perform(post("/ingest/dynamic/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://kubernetes.io/docs/busy\",\"docId\":\"k8s-busy\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        byte[] oversizedWebhook = new byte[WebhookController.MAX_WEBHOOK_PAYLOAD_BYTES + 1];
        mockMvc.perform(post("/ingest/dynamic/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedWebhook))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.status").value(413));

        when(dynamicIngestService.getVersionHistory("k8s-rbac"))
                .thenReturn(List.of(
                        new DocumentVersionInfo(1, "hash1", false, 4),
                        new DocumentVersionInfo(2, "hash2", true, 5)
                ));

        mockMvc.perform(get("/ingest/dynamic/versions/k8s-rbac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].isLatest").value(false))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].isLatest").value(true));
    }
}
