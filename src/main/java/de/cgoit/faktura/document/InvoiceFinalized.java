package de.cgoit.faktura.document;

/**
 * Published inside the transaction that finalizes an invoice, after the invoice is locked. A rollback of the
 * transaction also rolls back what listeners did.
 *
 * @param documentId id of the finalized invoice
 */
public record InvoiceFinalized(String documentId) {
}
