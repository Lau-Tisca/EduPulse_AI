package com.edupulse.ingestdynamic.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.InvalidWebhookSignatureException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HmacSignatureVerifierTest {

    private static HmacSignatureVerifier verifierWithSecret(String secret) {
        IngestDynamicProperties props = new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook(secret)
        );
        return new HmacSignatureVerifier(props);
    }

    @Test
    void acceptsValidHmacSha256Signature() {
        String secret = "super-secret-hmac-key";
        byte[] payload = "{\"url\":\"https://kubernetes.io/docs/home/\"}".getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + HmacSignatureVerifier.computeHex(payload, secret);

        assertThatCode(() -> verifierWithSecret(secret).verify(payload, signature))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingInvalidMalformedSignatureOrBlankSecret() {
        String secret = "super-secret-hmac-key";
        byte[] payload = "{\"url\":\"https://kubernetes.io/docs/home/\"}".getBytes(StandardCharsets.UTF_8);
        String validSig = "sha256=" + HmacSignatureVerifier.computeHex(payload, secret);

        HmacSignatureVerifier verifier = verifierWithSecret(secret);

        assertThatThrownBy(() -> verifier.verify(payload, null))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, "sha1=deadbeef"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(payload, "sha256=0000000000000000"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("{\"tampered\":true}".getBytes(StandardCharsets.UTF_8), validSig))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifierWithSecret("").verify(payload, validSig))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }
}
