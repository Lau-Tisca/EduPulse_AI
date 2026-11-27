package com.edupulse.ingestdynamic.parser;

import com.edupulse.ingestdynamic.exception.ContentParseException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Cleans HTML pages with Jsoup, stripping navigation, footers, scripts, styles, sidebars, and ad
 * boilerplate while preserving heading/paragraph structure from the primary content region.
 */
@Component
public class HtmlContentParser {

    private static final String BOILERPLATE_SELECTOR = String.join(", ",
            "nav",
            "footer",
            "script",
            "style",
            "aside",
            "noscript",
            "form",
            "svg",
            "iframe",
            "button",
            "template",
            "[role=navigation]",
            "[role=banner]",
            "[role=contentinfo]",
            "[role=complementary]",
            ".cookie-banner",
            "#cookie-banner",
            ".advertisement",
            ".ads",
            ".ad-container",
            ".sidebar",
            ".td-sidebar",
            ".td-sidebar-toc",
            ".breadcrumb",
            ".breadcrumbs",
            ".td-page-meta",
            ".edit-this-page"
    );

    private static final String BLOCK_ELEMENTS =
            "h1, h2, h3, h4, h5, h6, p, li, tr, pre, blockquote, dt, dd, section, article";

    public List<org.springframework.ai.document.Document> parse(byte[] htmlBytes, String baseUri) {
        if (htmlBytes == null || htmlBytes.length == 0) {
            throw new ContentParseException("HTML payload is empty: " + baseUri);
        }
        String html = new String(htmlBytes, StandardCharsets.UTF_8);
        Document jsoupDoc = Jsoup.parse(html, baseUri == null ? "" : baseUri);

        // Remove boilerplate elements globally before selecting content root.
        jsoupDoc.select(BOILERPLATE_SELECTOR).remove();

        Element root = selectPrimaryContent(jsoupDoc);
        String extracted = extractStructuredText(root);
        String normalized = TextNormalizer.normalize(extracted);

        if (normalized.isBlank()) {
            throw new ContentParseException("No extractable main text found after HTML boilerplate removal: " + baseUri);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("page", 1);
        String title = jsoupDoc.title();
        if (!title.isBlank()) {
            metadata.put("title", TextNormalizer.normalize(title));
        }
        return List.of(new org.springframework.ai.document.Document(normalized, metadata));
    }

    private Element selectPrimaryContent(Document doc) {
        Element main = doc.selectFirst("main");
        if (main != null && !main.text().isBlank()) {
            return main;
        }
        Element article = doc.selectFirst("article");
        if (article != null && !article.text().isBlank()) {
            return article;
        }
        return doc.body() != null ? doc.body() : doc;
    }

    private String extractStructuredText(Element root) {
        // Insert newline markers around block elements and <br>, plus spaces between table cells,
        // without globally rewriting literal "\n" sequences inside code snippets.
        Element copy = root.clone();
        for (Element br : copy.select("br")) {
            br.replaceWith(new TextNode("\n"));
        }
        for (Element cell : copy.select("td, th")) {
            cell.appendText(" ");
        }
        for (Element block : copy.select(BLOCK_ELEMENTS)) {
            block.prependText("\n");
            block.appendText("\n");
        }
        return copy.wholeText();
    }
}
