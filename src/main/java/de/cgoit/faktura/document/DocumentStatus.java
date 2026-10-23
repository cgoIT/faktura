package de.cgoit.faktura.document;

/**
 * Lifecycle status of a document. Only {@link #DRAFT} documents can be changed or deleted. Paid and overdue are not
 * stored; they are derived from payments and the due date.
 */
public enum DocumentStatus {
    DRAFT, FINAL, CANCELLED
}
