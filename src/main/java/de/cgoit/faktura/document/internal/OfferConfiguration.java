package de.cgoit.faktura.document.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the settings of offers.
 */
@Configuration
@EnableConfigurationProperties(OfferProperties.class)
public class OfferConfiguration {
}
