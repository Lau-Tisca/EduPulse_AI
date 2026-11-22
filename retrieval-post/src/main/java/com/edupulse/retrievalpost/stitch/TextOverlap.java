package com.edupulse.retrievalpost.stitch;

/**
 * Joins consecutive chunks while removing the text they share. Ingestion prefixes every chunk with the
 * last {@code APP_CHUNK_OVERLAP} (64) tokens of the previous one, so without trimming the stitched
 * context would repeat that text and waste the token budget.
 */
final class TextOverlap {

    /** Shorter suffix/prefix matches are treated as coincidence, not chunk overlap. */
    static final int MIN_OVERLAP_CHARS = 20;

    /** 64 tokens are ~250–400 chars; the window leaves ample margin while keeping the scan linear and small. */
    static final int MAX_OVERLAP_CHARS = 2000;

    private TextOverlap() {
    }

    /**
     * @return {@code previous + next} with the longest suffix of {@code previous} that is also a prefix of
     *         {@code next} removed once; joined by a newline when no significant overlap exists
     */
    static String join(String previous, String next) {
        if (previous == null || previous.isEmpty()) {
            return next == null ? "" : next;
        }
        if (next == null || next.isEmpty()) {
            return previous;
        }
        int overlap = overlapLength(previous, next);
        if (overlap >= MIN_OVERLAP_CHARS) {
            return previous + next.substring(overlap);
        }
        return previous + "\n" + next;
    }

    /**
     * Length of the longest suffix of {@code previous} equal to a prefix of {@code next}, using the KMP prefix
     * function over {@code nextPrefix + '\u0000' + previousSuffix} (linear time, no regex).
     */
    static int overlapLength(String previous, String next) {
        int window = Math.min(MAX_OVERLAP_CHARS, Math.min(previous.length(), next.length()));
        String combined = next.substring(0, window) + '\u0000' + previous.substring(previous.length() - window);
        int[] prefix = new int[combined.length()];
        for (int i = 1; i < combined.length(); i++) {
            int k = prefix[i - 1];
            while (k > 0 && combined.charAt(i) != combined.charAt(k)) {
                k = prefix[k - 1];
            }
            if (combined.charAt(i) == combined.charAt(k)) {
                k++;
            }
            prefix[i] = k;
        }
        return prefix[combined.length() - 1];
    }
}
