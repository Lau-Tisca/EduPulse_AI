package com.edupulse.retrievalpost.stitch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextOverlapTest {

    private static final String SHARED = "Pods share a network namespace and storage volumes.";

    @Test
    void removesTextRepeatedAtTheStartOfTheNextChunk() {
        String previous = "A Pod is the smallest deployable unit. " + SHARED;
        String next = SHARED + " Containers in a Pod are co-scheduled.";

        String joined = TextOverlap.join(previous, next);

        assertThat(joined).isEqualTo("A Pod is the smallest deployable unit. " + SHARED
                + " Containers in a Pod are co-scheduled.");
        assertThat(joined.indexOf(SHARED)).isEqualTo(joined.lastIndexOf(SHARED));
    }

    @Test
    void joinsWithNewlineWhenChunksDoNotOverlap() {
        assertThat(TextOverlap.join("First chunk ends here.", "Second chunk starts here."))
                .isEqualTo("First chunk ends here.\nSecond chunk starts here.");
    }

    @Test
    void ignoresShortCoincidentalOverlap() {
        String previous = "The kubelet runs on every node";
        String next = "node affinity rules constrain scheduling";

        assertThat(TextOverlap.overlapLength(previous, next)).isEqualTo(4);
        assertThat(TextOverlap.join(previous, next)).isEqualTo(previous + "\n" + next);
    }

    @Test
    void handlesEmptySides() {
        assertThat(TextOverlap.join("", "next")).isEqualTo("next");
        assertThat(TextOverlap.join("previous", "")).isEqualTo("previous");
        assertThat(TextOverlap.join(null, null)).isEmpty();
    }

    @Test
    void keepsWholeTextWhenNextIsFullyContainedAsSuffix() {
        String previous = "Intro text. " + SHARED;

        assertThat(TextOverlap.join(previous, SHARED)).isEqualTo(previous);
    }
}
