package de.cgoit.faktura.tax;

import java.util.List;

/**
 * Pre-filled values for a new tax profile of a regime, read from the application configuration.
 *
 * @param allowedCategories EN 16931 categories allowed on items; empty or {@code null} allows every category
 */
public record TaxDefaults(int rateBp, String category, String exemptionText, String noticeText,
        List<String> allowedCategories) {
}
