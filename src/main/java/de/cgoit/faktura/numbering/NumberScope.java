package de.cgoit.faktura.numbering;

/**
 * Area that owns its own number sequence and number pattern.
 */
public enum NumberScope {
    INVOICE,
    OFFER,
    CANCELLATION,
    DUNNING,
    CUSTOMER,
    CATALOG
}
