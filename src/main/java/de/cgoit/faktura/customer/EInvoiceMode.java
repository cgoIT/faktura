package de.cgoit.faktura.customer;

/**
 * Which electronic invoice a customer receives. {@code XRECHNUNG} is reserved for a later step and is not offered
 * in the customer form yet.
 */
public enum EInvoiceMode {
    NONE, ZUGFERD, XRECHNUNG
}
