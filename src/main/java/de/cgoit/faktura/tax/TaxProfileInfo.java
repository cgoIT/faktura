package de.cgoit.faktura.tax;

import java.time.LocalDate;
import java.util.List;

/**
 * Tax behaviour of the company for a period of time.
 *
 * @param validTo         last day of validity, {@code null} while the profile is open-ended
 * @param defaultRateBp   default tax rate in basis points (1900 = 19 %)
 * @param defaultCategory default EN 16931 tax category, e.g. {@code S} or {@code E}
 * @param exemptionText   reason for a tax exemption, printed and used in the e-invoice
 * @param noticeText      additional notice printed on documents
 * @param allowedCategories categories allowed on items of documents under this profile; empty allows every category
 */
public record TaxProfileInfo(
        String id,
        LocalDate validFrom,
        LocalDate validTo,
        TaxRegime regime,
        int defaultRateBp,
        String defaultCategory,
        String exemptionText,
        String noticeText,
        List<String> allowedCategories) {

    /**
     * Tells whether items of the given category may be used under this profile.
     */
    public boolean allows(String category) {
        return allowedCategories.isEmpty() || allowedCategories.contains(category);
    }

    /**
     * Tells whether the profile applies on the given day.
     */
    public boolean isValidOn(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
    }
}
