package com.edupulse.ingestdynamic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupulse.ingestdynamic.exception.ContentParseException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ContentParserTest {

    private final HtmlContentParser htmlParser = new HtmlContentParser();
    private final MarkdownContentParser markdownParser = new MarkdownContentParser();
    private final ContentParser contentParser = new ContentParser(new PdfDocumentParser(), htmlParser, markdownParser);

    @Test
    void stripsHtmlBoilerplateAndPreservesMainContent() {
        String html = """
                <!DOCTYPE html>
                <html>
                  <head>
                    <title>Kubernetes RBAC</title>
                    <style>body { color: black; }</style>
                    <script>alert('tracking');</script>
                  </head>
                  <body>
                    <nav>Home | Docs | Blog</nav>
                    <aside class="sidebar">Sidebar Links</aside>
                    <div class="cookie-banner">Accept cookies</div>
                    <main>
                      <h1>Using RBAC Authorization</h1>
                      <p>Role-based access control regulates access to Kubernetes resources.</p>
                    </main>
                    <footer>Copyright CNCF 2026</footer>
                  </body>
                </html>
                """;

        List<Document> docs = contentParser.parse(
                html.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=UTF-8",
                "https://kubernetes.io/docs/reference/access-authn-authz/rbac/"
        );

        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getMetadata()).containsEntry("page", 1);
        assertThat(docs.getFirst().getText())
                .contains("Using RBAC Authorization")
                .contains("Role-based access control regulates access to Kubernetes resources.")
                .doesNotContain("Home | Docs | Blog")
                .doesNotContain("Sidebar Links")
                .doesNotContain("Accept cookies")
                .doesNotContain("alert('tracking')")
                .doesNotContain("Copyright CNCF");
    }

    @Test
    void rejectsBoilerplateOnlyHtml() {
        String html = "<html><body><nav>Menu</nav><script>console.log(1)</script><footer>End</footer></body></html>";

        assertThatThrownBy(() -> htmlParser.parse(html.getBytes(StandardCharsets.UTF_8), "https://kubernetes.io/empty"))
                .isInstanceOf(ContentParseException.class);
    }

    @Test
    void stripsYamlFrontmatterHtmlCommentsAndNulBytesFromMarkdown() {
        String md = """
                ---
                title: Horizontal Pod Autoscaling
                weight: 10
                ---
                <!-- internal note -->
                # HorizontalPodAutoscaler

                HPA scales pods based on CPU\u0000 utilization.
                """;

        List<Document> docs = contentParser.parse(
                md.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                "https://raw.githubusercontent.com/kubernetes/website/main/content/en/docs/tasks/run-application/hpa.md"
        );

        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getMetadata()).containsEntry("page", 1);
        assertThat(docs.getFirst().getText())
                .contains("# HorizontalPodAutoscaler")
                .contains("HPA scales pods based on CPU utilization.")
                .doesNotContain("weight: 10")
                .doesNotContain("internal note")
                .doesNotContain("\u0000");
    }
}
