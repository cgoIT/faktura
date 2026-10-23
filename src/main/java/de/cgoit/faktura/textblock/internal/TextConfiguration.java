package de.cgoit.faktura.textblock.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the text configuration properties.
 */
@Configuration
@EnableConfigurationProperties(TextProperties.class)
public class TextConfiguration {
}
