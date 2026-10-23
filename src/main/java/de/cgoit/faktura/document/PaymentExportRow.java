package de.cgoit.faktura.document;

import java.time.LocalDate;

/**
 * A payment as listed in the accounting export.
 *
 * @param amountCents negative for a reversing entry
 * @param reversal    the entry reverses an earlier payment
 */
public record PaymentExportRow(
        LocalDate date,
        String invoiceNumber,
        long amountCents,
        PaymentMethod method,
        String reference,
        boolean reversal) {

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "PaymentExportRow[invoiceNumber=" + invoiceNumber + "]";
    }
}
