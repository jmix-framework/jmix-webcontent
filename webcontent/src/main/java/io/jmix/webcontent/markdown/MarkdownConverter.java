package io.jmix.webcontent.markdown;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders Markdown to HTML for {@link io.jmix.webcontent.entity.WebContentType#MD} content.
 * <p>
 * Deliberately stateless and dependency-free beyond commonmark, so the exact same conversion is available
 * three ways: from the save listener at runtime, from a consumer's build through
 * {@code WebContentMigrationGenerator}, and from tests. Build-time and save-time HTML must agree, otherwise
 * a generated migration would differ from what the admin UI produces for the same source.
 * <p>
 * Enabled extensions: GFM tables, autolinks, and heading anchors (so a rendered document can be deep-linked).
 * Raw HTML in the source is escaped rather than passed through — content is admin-authored but this is
 * injected into a page with {@code innerHTML}, and escaping keeps a stored document from becoming a scripting
 * vector.
 */
@Component("wc_MarkdownConverter")
public class MarkdownConverter {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownConverter() {
        List<org.commonmark.Extension> extensions = List.of(
                TablesExtension.create(),
                AutolinkExtension.create(),
                HeadingAnchorExtension.create());
        this.parser = Parser.builder()
                .extensions(extensions)
                .build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .build();
    }

    /**
     * @param markdown Markdown source; {@code null} or blank yields an empty string
     * @return rendered HTML fragment (no {@code <html>}/{@code <body>} wrapper)
     */
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}
