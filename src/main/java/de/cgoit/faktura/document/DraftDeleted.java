package de.cgoit.faktura.document;

/**
 * Published inside the transaction that deletes an invoice draft, so other modules can tidy up what refers to it.
 *
 * @param documentId id of the deleted draft
 */
public record DraftDeleted(String documentId) {
}
