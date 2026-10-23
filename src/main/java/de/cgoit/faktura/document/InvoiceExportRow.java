package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.List;

/**
 * An invoice or cancellation as listed in the accounting export. All amounts are the stored totals; a cancellation
 * carries negative amounts.
 *
 * @param customerNumber customer number from the recipient snapshot
 * @param netCents       net total including shipping
 * @param shippingCents  net shipping charge, already part of {@code netCents}
 * @param taxTotals      stored tax per category and rate
 * @param state          payment state at the day of the export, {@code null} for a cancellation
 * @param paidOn         date of the last payment if the invoice is paid, otherwise {@code null}
 */
public record InvoiceExportRow(
        String number,
        DocumentType type,
        LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        String customerNumber,
        String customerName,
        String customerReference,
        long netCents,
        long shippingCents,
        long grossCents,
        List<TaxTotal> taxTotals,
        PaymentState state,
        LocalDate paidOn) {

    /**
     * Shows only the number: customer name and reference are personal data.
     */
    @Override
    public String toString() {
        return "InvoiceExportRow[number=" + number + "]";
    }
}
