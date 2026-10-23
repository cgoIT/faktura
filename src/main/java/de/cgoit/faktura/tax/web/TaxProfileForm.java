package de.cgoit.faktura.tax.web;

import java.time.LocalDate;

import de.cgoit.faktura.tax.NewTaxProfile;
import de.cgoit.faktura.tax.TaxRegime;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form data for a new tax profile.
 */
public record TaxProfileForm(
        @NotNull(message = "{validation.required}") TaxRegime regime,
        @NotNull(message = "{validation.required}")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom) {

    NewTaxProfile toRequest() {
        return new NewTaxProfile(regime, validFrom);
    }
}
