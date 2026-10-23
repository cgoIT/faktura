package de.cgoit.faktura.document.internal;

import java.time.LocalDate;
import java.util.Map;

import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.tax.TaxRegime;

/**
 * Copy of the tax profile that applied to a document, stored as JSON when the document is finalized.
 */
public record TaxSnapshot(
        String profileId,
        LocalDate validFrom,
        LocalDate validTo,
        TaxRegime regime,
        int defaultRateBp,
        String defaultCategory,
        String exemptionText,
        String noticeText,
        Map<String, String> categoryReasons) {

    /**
     * Snapshots stored before the reasons existed have none.
     */
    public TaxSnapshot {
        categoryReasons = categoryReasons == null ? Map.of() : Map.copyOf(categoryReasons);
    }


    public static TaxSnapshot of(TaxProfileInfo profile, Map<String, String> categoryReasons) {
        return new TaxSnapshot(profile.id(), profile.validFrom(), profile.validTo(), profile.regime(),
                profile.defaultRateBp(), profile.defaultCategory(), profile.exemptionText(), profile.noticeText(),
                categoryReasons);
    }
}
