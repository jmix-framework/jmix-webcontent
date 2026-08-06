package io.jmix.webcontent.listener;

import io.jmix.core.event.EntitySavingEvent;
import io.jmix.webcontent.entity.WebContent;
import io.jmix.webcontent.entity.WebContentType;
import io.jmix.webcontent.markdown.MarkdownConverter;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link WebContentType#MD} item's Markdown {@code source} into its HTML {@code contents} on every
 * save, so consumers only ever read HTML.
 * <p>
 * This lives in an {@link EntitySavingEvent} rather than in the detail view because it must hold for every
 * write path — {@code DataManager} from a service, REST, a test — not just the admin form. A {@code null}
 * type is treated as {@code HTML} (rows predating the type column), so untyped content is never touched.
 */
@Component("wc_WebContentSavingListener")
public class WebContentSavingListener {

    private final MarkdownConverter markdownConverter;

    public WebContentSavingListener(MarkdownConverter markdownConverter) {
        this.markdownConverter = markdownConverter;
    }

    @EventListener
    public void onWebContentSaving(EntitySavingEvent<WebContent> event) {
        WebContent webContent = event.getEntity();
        if (webContent.getType() != WebContentType.MD) {
            return;
        }
        webContent.setContents(markdownConverter.toHtml(webContent.getSource()));
    }
}
