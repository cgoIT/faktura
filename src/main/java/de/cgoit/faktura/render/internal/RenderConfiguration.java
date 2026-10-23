package de.cgoit.faktura.render.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the render configuration properties.
 */
@Configuration
@EnableConfigurationProperties(RenderProperties.class)
public class RenderConfiguration {
}
