package de.cgoit.faktura.dev;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * Reads the UI texts straight from the source directory and checks the files on every request, so a changed
 * {@code messages_de.properties} applies without a restart. The bean name replaces the one of Spring Boot. The
 * application has to be started from the project root.
 */
@Configuration
@Profile("dev")
public class DevMessages {

    @Bean
    MessageSource messageSource() {
        var source = new ReloadableResourceBundleMessageSource();

        source.setBasename("file:src/main/resources/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setCacheSeconds(0);

        return source;
    }
}
