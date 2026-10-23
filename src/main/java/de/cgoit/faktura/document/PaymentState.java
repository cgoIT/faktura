package de.cgoit.faktura.document;

/**
 * Payment state of an invoice. It is derived from payments and totals and never stored. Overdue is a separate flag
 * because an invoice can be open and overdue, or partly paid and overdue, at the same time.
 */
public enum PaymentState {
    OPEN, PARTIALLY_PAID, PAID, CANCELLED
}
