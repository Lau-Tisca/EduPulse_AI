package com.edupulse.retrievalpre.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.edupulse.retrievalpre.normalize.QueryNormalizer;
import org.junit.jupiter.api.Test;

class QueryControllerTest {

    private final QueryController controller = new QueryController(new QueryNormalizer());

    @Test
    void returnsRawAndNormalizedQuery() {
        Map<String, String> result = controller.normalize("Hi, can you please tell me how HPA works?");

        assertThat(result)
                .containsEntry("raw", "Hi, can you please tell me how HPA works?")
                .containsEntry("normalized", "how HPA works");
    }

    @Test
    void cleanQueryIsReturnedUnchanged() {
        Map<String, String> result = controller.normalize("What is a pod");

        assertThat(result).containsEntry("normalized", "What is a pod");
    }
}
