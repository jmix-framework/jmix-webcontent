package io.jmix.webcontent.view.webcontent;

import io.jmix.webcontent.entity.WebContent;
import io.jmix.webcontent.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "webContents", layout = MainView.class)
@ViewController("WebContent.list")
@ViewDescriptor("web-content-list-view.xml")
@LookupComponent("webContentsDataGrid")
@DialogMode(width = "64em")
public class WebContentListView extends StandardListView<WebContent> {
}