package io.jmix.webcontent.service;

import io.jmix.core.DataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.webcontent.entity.WebContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service("wc_WebContentService")
public class WebContentService {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    public WebContent findBySlug(String slug) {
        return dataManager.load(WebContent.class)
                .query("select distinct wc from WebContent wc " +
                        "where wc.slug = :slug and (wc.lang = :lang or wc.lang = :lang_default)")
                .parameter("slug", slug)
                .parameter("lang", currentAuthentication.getLocale().getLanguage())
                .parameter("lang_default", Locale.ENGLISH.getLanguage())
                .one();
    }
}
