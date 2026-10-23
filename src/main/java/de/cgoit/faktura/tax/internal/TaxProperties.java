package de.cgoit.faktura.tax.internal;

import de.cgoit.faktura.tax.TaxDefaults;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default values of the tax regimes and the EN 16931 categories that carry a tax rate ({@code faktura.tax.*} in
 * application.yml). {@code categoryReasons} holds the default reason per category that needs one.
 */
@ConfigurationProperties("faktura.tax")
public record TaxProperties(TaxDefaults smallBusiness, TaxDefaults regular, List<String> ratedCategories,
        Map<String, String> categoryReasons) {
}
