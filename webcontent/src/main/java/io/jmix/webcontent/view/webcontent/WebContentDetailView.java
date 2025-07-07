package io.jmix.webcontent.view.webcontent;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.webcontent.entity.WebContent;

@Route(value = "webContents/:id", layout = DefaultMainViewParent.class)
@ViewController("WebContent.detail")
@ViewDescriptor("web-content-detail-view.xml")
@EditedEntityContainer("webContentDc")
public class WebContentDetailView extends StandardDetailView<WebContent> {
}