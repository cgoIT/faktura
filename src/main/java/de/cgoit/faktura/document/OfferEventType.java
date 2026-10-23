package de.cgoit.faktura.document;

/**
 * Something that happened to a finalized offer. Expiry is not an event: it follows from the validity date.
 */
public enum OfferEventType {
    SENT, ACCEPTED, REJECTED
}
