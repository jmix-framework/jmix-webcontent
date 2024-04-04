package io.jmix.webcontent.view.main;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route("")
@ViewController("MainView")
@ViewDescriptor("main-view.xml")
public class MainView extends StandardMainView {
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getPath().isEmpty()) {
            event.forwardTo("/feedback");
        }
    }
}
