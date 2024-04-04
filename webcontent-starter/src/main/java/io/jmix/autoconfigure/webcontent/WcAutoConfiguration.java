package io.jmix.autoconfigure.webcontent;

import io.jmix.webcontent.WcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({WcConfiguration.class})
public class WcAutoConfiguration {
}

