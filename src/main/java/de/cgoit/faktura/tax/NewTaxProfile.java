package de.cgoit.faktura.tax;

import java.time.LocalDate;

/**
 * Request to start a new tax profile. Rate, category and texts are taken from the configured defaults of the regime
 * unless given.
 */
public record NewTaxProfile(TaxRegime regime, LocalDate validFrom) {
}
