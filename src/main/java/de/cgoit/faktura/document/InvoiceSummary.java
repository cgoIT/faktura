package de.cgoit.faktura.document;

import java.time.LocalDate;

/**
 * Row of the invoice list.
 *
 * @param number      {@code null} for drafts
 * @param status      display status: {@code CANCELLED} for an invoice that has a cancellation (derived, not stored)
 * @param customerName display name of the customer, empty if the customer is unknown
 * @param balance      payment situation today, {@code null} for drafts and cancellation documents
 * @param dunningSuggested a dunning letter is suggested for this invoice (set by the web layer from {@link DunningHints})
 */
public record InvoiceSummary(
        String id,
        DocumentType type,
        DocumentStatus status,
        String number,
        String customerId,
        String customerName,
        String subject,
        LocalDate issueDate,
        LocalDate dueDate,
        long grossTotalCents,
        InvoiceBalance balance,
        boolean dunningSuggested) {

    /**
     * Returns a copy that carries the dunning hint.
     */
    public InvoiceSummary withDunningSuggested(boolean suggested) {
        return new InvoiceSummary(id, type, status, number, customerId, customerName, subject, issueDate, dueDate,
                grossTotalCents, balance, suggested);
    }

    /**
     * Hides the customer name from logs.
     */
    @Override
    public String toString() {
        return "InvoiceSummary[id=" + id + "]";
    }
}
