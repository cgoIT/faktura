package de.cgoit.faktura.einvoice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the electronic invoice ({@code faktura.einvoice.*} in application.yml).
 *
 * @param defaultCountry  ISO 3166 alpha-2 code used when a sender or recipient has no country
 * @param defaultUnitCode UN/ECE unit code used when an item has no unit with a code
 * @param discountReason  text that names a discount in the XML
 */
@ConfigurationProperties("faktura.einvoice")
public record EInvoiceProperties(String defaultCountry, String defaultUnitCode, String discountReason) {
}
