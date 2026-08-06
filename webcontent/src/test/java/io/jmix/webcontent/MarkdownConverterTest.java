package io.jmix.webcontent;

import io.jmix.webcontent.markdown.MarkdownConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit test — the converter is deliberately Spring-free so the build-time tool can reuse it. */
class MarkdownConverterTest {

    private final MarkdownConverter converter = new MarkdownConverter();

    @Test
    void renders_headings_and_emphasis() {
        String html = converter.toHtml("# Title\n\nSome **bold** text.\n");

        assertTrue(html.contains("<h1"), html);
        assertTrue(html.contains("Title"), html);
        assertTrue(html.contains("<strong>bold</strong>"), html);
    }

    @Test
    void renders_gfm_tables() {
        String markdown = """
                | Column | Value |
                | --- | --- |
                | a | 1 |
                """;

        String html = converter.toHtml(markdown);

        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<th>Column</th>"), html);
        assertTrue(html.contains("<td>a</td>"), html);
    }

    @Test
    void renders_fenced_code_blocks_with_a_language_class() {
        String html = converter.toHtml("```java\nint x = 1;\n```\n");

        assertTrue(html.contains("<pre>"), html);
        assertTrue(html.contains("language-java"), html);
    }

    @Test
    void adds_heading_anchors_so_documents_can_be_deep_linked() {
        String html = converter.toHtml("## REST reception\n");

        assertTrue(html.contains("id=\"rest-reception\""), html);
    }

    @Test
    void escapes_raw_html_instead_of_passing_it_through() {
        String html = converter.toHtml("Hello <script>alert('x')</script>\n");

        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(!html.contains("<script>"), html);
    }

    @Test
    void treats_null_and_blank_source_as_empty_output() {
        assertEquals("", converter.toHtml(null));
        assertEquals("", converter.toHtml("   \n  "));
    }
}
