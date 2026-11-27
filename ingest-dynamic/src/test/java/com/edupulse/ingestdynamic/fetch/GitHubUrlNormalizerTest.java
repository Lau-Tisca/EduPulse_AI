package com.edupulse.ingestdynamic.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitHubUrlNormalizerTest {

    private final GitHubUrlNormalizer normalizer = new GitHubUrlNormalizer();

    @Test
    void rewritesGitHubBlobUrlToRawGitHubUserContent() {
        String input = "https://github.com/kubernetes/website/blob/main/content/en/docs/concepts/overview/what-is-kubernetes.md";
        String expected = "https://raw.githubusercontent.com/kubernetes/website/main/content/en/docs/concepts/overview/what-is-kubernetes.md";

        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void leavesNonBlobAndNonGitHubUrlsUnchanged() {
        String raw = "https://raw.githubusercontent.com/kubernetes/website/main/README.md";
        String k8s = "https://kubernetes.io/docs/concepts/security/rbac-good-practices/";
        String repoRoot = "https://github.com/kubernetes/kubernetes";

        assertThat(normalizer.normalize(raw)).isEqualTo(raw);
        assertThat(normalizer.normalize(k8s)).isEqualTo(k8s);
        assertThat(normalizer.normalize(repoRoot)).isEqualTo(repoRoot);
    }
}
