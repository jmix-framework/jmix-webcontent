package io.jmix.webcontent;

import io.jmix.core.DataManager;
import io.jmix.webcontent.entity.WebContent;
import io.jmix.webcontent.entity.WebContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Markdown is rendered to HTML on the {@code DataManager} path, not merely in the detail view — the
 * whole point of doing it in an {@code EntitySavingEvent}.
 */
@SpringBootTest
class WebContentSavingListenerTest {

    @Autowired
    DataManager dataManager;

    @Test
    void renders_markdown_source_into_contents_on_save() {
        WebContent content = newContent("listener-md", WebContentType.MD);
        content.setSource("# Guide\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n");

        WebContent saved = dataManager.save(content);

        assertEquals(WebContentType.MD, saved.getType());
        assertTrue(saved.getContents().contains("<h1"), saved.getContents());
        assertTrue(saved.getContents().contains("<table>"), saved.getContents());
        // The Markdown itself is preserved so it can be edited again.
        assertTrue(saved.getSource().startsWith("# Guide"), saved.getSource());
    }

    @Test
    void re_rendering_tracks_an_edited_source() {
        WebContent content = newContent("listener-md-edit", WebContentType.MD);
        content.setSource("First revision.\n");
        WebContent first = dataManager.save(content);

        first.setSource("Second revision.\n");
        WebContent second = dataManager.save(first);

        assertTrue(second.getContents().contains("Second revision."), second.getContents());
        assertFalse(second.getContents().contains("First revision."), second.getContents());
    }

    /** An HTML item's hand-authored contents must never be touched by the renderer. */
    @Test
    void leaves_html_content_alone() {
        WebContent content = newContent("listener-html", WebContentType.HTML);
        content.setContents("<p>Untouched &amp; verbatim</p>");

        WebContent saved = dataManager.save(content);

        assertEquals("<p>Untouched &amp; verbatim</p>", saved.getContents());
        assertNull(saved.getSource());
    }

    /** A new item defaults to HTML, so behaviour predating the type column is unchanged. */
    @Test
    void new_content_defaults_to_html() {
        assertEquals(WebContentType.HTML, dataManager.create(WebContent.class).getType());
    }

    private WebContent newContent(String slug, WebContentType type) {
        WebContent content = dataManager.create(WebContent.class);
        content.setTitle(slug);
        content.setSlug(slug);
        content.setLang("en");
        content.setType(type);
        return content;
    }
}
