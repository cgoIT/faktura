package de.cgoit.faktura.document;

/**
 * Where an offer stands. Derived from the status, the validity date and the recorded events; never stored, because a
 * finalized offer is never changed.
 */
public enum OfferState {
    DRAFT, OPEN, SENT, ACCEPTED, REJECTED, EXPIRED
}
