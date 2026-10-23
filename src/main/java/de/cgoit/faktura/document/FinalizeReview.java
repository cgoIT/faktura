package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.tax.TaxRegime;

/**
 * What the user checks before an invoice draft is finalized: the document as it would be finalized now, computed from
 * the posted form without storing anything.
 *
 * @param headerText     header text with all placeholders filled in, in the markup of {@code RichText}
 * @param footerText     footer text, likewise
 * @param exemptionText  legal text of the tax profile, may be {@code null}
 * @param customerReference reference of the customer, {@code null} if none
 * @param shippingName  label of the shipping costs, {@code null} if none or unnamed; the amount is in the calculation
 * @param einvoiceRules  rule identifiers the trial run of the e-invoice reported; empty if it passed or none is made
 * @param einvoiceFailed {@code true} if the trial run of the e-invoice failed
 * @param validUntil     for an offer the last day it is valid, {@code null} for an invoice
 */
public record FinalizeReview(
        String customerNumber,
        String customerName,
        List<String> addressLines,
        String contactName,
        EInvoiceMode einvoiceMode,
        TaxRegime regime,
        String exemptionText,
        LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        LocalDate dueDate,
        String subject,
        String headerText,
        String footerText,
        List<InvoiceItemInfo> items,
        Calculation calculation,
        long totalDiscountCents,
        String customerReference,
        String shippingName,
        boolean einvoiceFailed,
        List<String> einvoiceRules,
        LocalDate validUntil) {

    /**
     * Tells whether the document can be finalized as it is.
     */
    public boolean ok() {
        return !einvoiceFailed;
    }

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "FinalizeReview[customerNumber=" + customerNumber + "]";
    }
}
