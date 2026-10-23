package de.cgoit.faktura.textblock.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Salutation lines ({@code faktura.text.*} in application.yml). {@code {0}} is the name; texts use
 * {@link java.text.MessageFormat} rules.
 *
 * @param salutationMr      contact with salutation "Herr"; {0} is the last name
 * @param salutationMs      contact with salutation "Frau"; {0} is the last name
 * @param salutationNamed   contact without salutation; {0} is the full name
 * @param salutationGeneric no contact
 */
@ConfigurationProperties("faktura.text")
public record TextProperties(String salutationMr, String salutationMs, String salutationNamed,
        String salutationGeneric) {
}
