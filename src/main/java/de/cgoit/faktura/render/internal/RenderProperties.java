package de.cgoit.faktura.render.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable parts of the printed document ({@code faktura.render.*} in application.yml). Texts with {@code {0}}
 * placeholders use {@link java.text.MessageFormat} rules.
 *
 * @param logoPath          logo file, blank for the placeholder logo
 * @param sideText          vertical text at the right edge
 * @param cancellationText  sentence of a cancellation; {0} is the cancelled number, {1} its date
 */
@ConfigurationProperties("faktura.render")
public record RenderProperties(
        String logoPath,
        String sideText,
        String cancellationText) {
}
