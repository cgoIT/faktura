package de.cgoit.faktura.tax.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the tax configuration properties.
 */
@Configuration
@EnableConfigurationProperties(TaxProperties.class)
public class TaxConfiguration {
}
