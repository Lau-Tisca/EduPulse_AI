package com.edupulse.ingestdynamic.chunking;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Computes deterministic SHA-256 hex checksums for document content.
 */
@Service
public class ChecksumService {

    public String sha256(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("Content bytes must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable in JVM", ex);
        }
    }
}
