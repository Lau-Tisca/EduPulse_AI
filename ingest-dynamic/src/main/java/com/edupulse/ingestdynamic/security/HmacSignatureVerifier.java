package com.edupulse.ingestdynamic.security;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.InvalidWebhookSignatureException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies {@code X-Hub-Signature-256} ({@code sha256=<hex>}) HMAC-SHA256 webhook signatures
 * against {@code VENDOR_WEBHOOK_SECRET} using constant-time byte comparison.
 */
@Component
public class HmacSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final Logger log = LoggerFactory.getLogger(HmacSignatureVerifier.class);

    private final IngestDynamicProperties properties;

    public HmacSignatureVerifier(IngestDynamicProperties properties) {
        this.properties = properties;
    }

    public void verify(byte[] rawPayload, String signatureHeader) {
        if (!properties.webhook().hasSecret()) {
            log.warn("Rejected webhook request: VENDOR_WEBHOOK_SECRET is not configured");
            throw new InvalidWebhookSignatureException("Webhook secret is not configured");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Rejected webhook request: missing {} header", SIGNATURE_HEADER);
            throw new InvalidWebhookSignatureException("Missing " + SIGNATURE_HEADER + " header");
        }
        String trimmed = signatureHeader.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(SIGNATURE_PREFIX)) {
            log.warn("Rejected webhook request: malformed {} prefix", SIGNATURE_HEADER);
            throw new InvalidWebhookSignatureException("Invalid " + SIGNATURE_HEADER + " format (expected sha256=<hex>)");
        }

        byte[] payload = rawPayload == null ? new byte[0] : rawPayload;
        String expected = SIGNATURE_PREFIX + computeHex(payload, properties.webhook().secret());
        String provided = SIGNATURE_PREFIX + trimmed.substring(SIGNATURE_PREFIX.length()).toLowerCase(Locale.ROOT);

        boolean valid = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            log.warn("Rejected webhook request: {} HMAC mismatch", SIGNATURE_HEADER);
            throw new InvalidWebhookSignatureException("Invalid " + SIGNATURE_HEADER + " signature");
        }
    }

    public static String computeHex(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", ex);
        }
    }
}
