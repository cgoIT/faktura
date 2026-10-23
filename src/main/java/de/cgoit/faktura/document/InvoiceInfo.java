package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.List;

/**
 * A stored invoice with its items. Only a {@link DocumentStatus#DRAFT} can be changed; the number is {@code null}
 * until the invoice is finalized. The totals of a draft are the result of the last save.
 *
 * @param refDocumentId the cancelled invoice, for a cancellation
 * @param cancelledById the cancellation, for an invoice that was cancelled (the invoice itself stays {@code FINAL})
 * @param recipientName name printed on a finalized document (from the snapshot), {@code null} for a draft
 * @param createdBy username that created the draft, {@code null} for documents from before users existed
 * @param version number of saves of the draft; the editor sends it back so a parallel save is detected
 * @param customerReference reference of the customer, {@code null} if none
 * @param shipping shipping costs, net; negative on a cancellation, amount 0 if none
 * @param validUntil last day an offer is valid, {@code null} for invoices and cancellations
 */
public record InvoiceInfo(
        String id,
        DocumentStatus status,
        String number,
        String customerId,
        LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        LocalDate dueDate,
        String subject,
        String headerText,
        String footerText,
        TotalDiscount totalDiscount,
        List<InvoiceItemInfo> items,
        long netTotalCents,
        long taxTotalCents,
        long grossTotalCents,
        List<TaxTotal> taxTotals,
        DocumentType type,
        String refDocumentId,
        String cancelledById,
        String recipientName,
        String contactId,
        String createdBy,
        long version,
        String customerReference,
        ShippingCharge shipping,
        LocalDate validUntil) {

    public boolean isDraft() {
        return status == DocumentStatus.DRAFT;
    }

    /**
     * Tells whether a cancellation refers to this invoice. Cancelled is derived: the invoice itself never changes.
     */
    public boolean isCancelled() {
        return cancelledById != null;
    }

    /**
     * Shows only the identifier: subject and texts must not end up in logs.
     */
    @Override
    public String toString() {
        return "InvoiceInfo[id=" + id + ", status=" + status + "]";
    }
}
