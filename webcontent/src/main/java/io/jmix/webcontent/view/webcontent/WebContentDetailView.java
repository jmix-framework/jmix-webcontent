package io.jmix.webcontent.view.webcontent;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.markdowneditor.MarkdownEditor;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.webcontent.entity.WebContent;
import io.jmix.webcontent.entity.WebContentType;

/**
 * Editor for a single content item. The body is edited in the format the item declares: a
 * {@link WebContentType#MD} item gets the Markdown editor bound to {@code source} — its HTML is rendered on
 * save by {@code WebContentSavingListener}, so the HTML field is hidden to make clear it is derived rather
 * than hand-editable. An {@code HTML} item gets the plain text area over {@code contents}, as before.
 */
@Route(value = "webContents/:id", layout = DefaultMainViewParent.class)
@ViewController("WebContent.detail")
@ViewDescriptor("web-content-detail-view.xml")
@EditedEntityContainer("webContentDc")
public class WebContentDetailView extends StandardDetailView<WebContent> {

    @ViewComponent
    private JmixComboBox<WebContentType> typeField;
    @ViewComponent
    private MarkdownEditor sourceField;
    @ViewComponent
    private JmixTextArea contentsField;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        applyType(getEditedEntity().getType());
    }

    @Subscribe("typeField")
    public void onTypeFieldValueChange(
            final AbstractField.ComponentValueChangeEvent<JmixComboBox<WebContentType>, WebContentType> event) {
        applyType(event.getValue());
    }

    /** A null type means an item created before the type column existed: treat it as HTML. */
    private void applyType(WebContentType type) {
        boolean markdown = type == WebContentType.MD;
        sourceField.setVisible(markdown);
        contentsField.setVisible(!markdown);
    }
}
