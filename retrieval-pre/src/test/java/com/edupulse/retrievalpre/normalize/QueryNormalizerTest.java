package com.edupulse.retrievalpre.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer();

    // ---------- Success flows: 18 cases (input | expected) ----------

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Hi, can you please tell me how HPA works? | how HPA works",
            "Could you explain how pods are scheduled? | how pods are scheduled",
            "Hello! What is a PVC? | What is a PVC",
            "Please describe the CNI plugin | describe the CNI plugin",
            "I want to know what RBAC is | what RBAC is",
            "What is a CRD? | What is a CRD",
            "'What is a CRD ?  ' | What is a CRD",
            "How does OCI work??? | How does OCI work",
            "explain TLS, thanks! | explain TLS",
            "How do I scale deployments, please? | How do I scale deployments",
            "Hey, hi, could you please show me kubectl commands? | kubectl commands",
            "'   What   is   HPA?   ' | What is HPA",
            "Good morning, I would like to know how ingress works | how ingress works",
            "Show me the K8s architecture | Show me the K8s architecture",
            "history of Kubernetes | history of Kubernetes",
            "What is a pod | What is a pod",
            "Would you please explain PVC binding? | PVC binding",
            "please? | please?"
    })
    void normalizesQuery(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    // ---------- Edge cases ----------

    @Test
    void nullBecomesEmptyString() {
        assertThat(normalizer.normalize(null)).isEmpty();
    }

    @Test
    void blankBecomesEmptyString() {
        assertThat(normalizer.normalize("     ")).isEmpty();
    }

    @Test
    void acronymsKeepTheirCase() {
        assertThat(normalizer.normalize("Can you explain HPA vs VPA?")).isEqualTo("HPA vs VPA");
    }

    @Test
    void veryLongInputIsCut() {
        String result = normalizer.normalize("word ".repeat(10_000));

        assertThat(result.length()).isLessThanOrEqualTo(QueryNormalizer.MAX_QUERY_LENGTH);
    }

    // ---------- Idempotency: normalizing twice gives the same result ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "Hi, can you please tell me how HPA works?",
            "Hey, hi, could you please show me kubectl commands?",
            "explain TLS, thanks!",
            "How do I scale deployments, please?",
            "please?",
            "   What   is   HPA?   ",
            "What is a pod"
    })
    void normalizationIsIdempotent(String input) {
        String once = normalizer.normalize(input);
        String twice = normalizer.normalize(once);

        assertThat(twice).isEqualTo(once);
    }

    // ---------- ReDoS: adversarial inputs must finish in < 50 ms ----------

    static Stream<String> adversarialInputs() {
        return Stream.of(
                "a".repeat(100_000),
                " ".repeat(100_000) + "x",
                "?".repeat(100_000) + "x",
                "please ".repeat(20_000),
                ", ".repeat(50_000) + "thanks!x",
                "hi ".repeat(30_000),
                "can you " .repeat(20_000));
    }

    @ParameterizedTest
    @MethodSource("adversarialInputs")
    void adversarialInputFinishesFast(String input) {
        // Warm up the JVM so we measure the regex work, not class loading.
        for (int i = 0; i < 20; i++) {
            normalizer.normalize("Hi, can you please explain HPA?");
        }

        long start = System.nanoTime();
        normalizer.normalize(input);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(50);
    }
}
