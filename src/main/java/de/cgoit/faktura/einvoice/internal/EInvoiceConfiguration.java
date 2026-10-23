package de.cgoit.faktura.einvoice.internal;

import de.cgoit.faktura.einvoice.EInvoiceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the e-invoice configuration properties.
 */
@Configuration
@EnableConfigurationProperties(EInvoiceProperties.class)
public class EInvoiceConfiguration {
}
