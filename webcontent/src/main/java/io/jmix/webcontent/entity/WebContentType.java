package io.jmix.webcontent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

/**
 * How a {@link WebContent} item's body is authored.
 * <p>
 * Either way {@link WebContent#getContents()} always returns servable HTML — that is the contract consumers
 * rely on. {@link #MD} only changes where that HTML comes from: it is rendered from
 * {@link WebContent#getSource()} when the item is saved, rather than typed in by hand.
 */
public enum WebContentType implements EnumClass<String> {

    /** {@code contents} is authored directly as HTML. The historical (and default) behaviour. */
    HTML("HTML"),

    /** {@code source} is authored as Markdown and rendered into {@code contents} on save. */
    MD("MD");

    private final String id;

    WebContentType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static WebContentType fromId(String id) {
        for (WebContentType value : WebContentType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
