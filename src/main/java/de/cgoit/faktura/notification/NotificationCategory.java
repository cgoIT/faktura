package de.cgoit.faktura.notification;

/**
 * Kind of item the daily notification mail reports.
 */
public enum NotificationCategory {
    /** A draft invoice generated from a recurring plan waits for approval. */
    DRAFT,
    /** A recurring plan could not generate its draft. */
    PLAN_ERROR,
    /** An invoice is overdue. */
    OVERDUE,
    /** A dunning letter is suggested for an overdue invoice. */
    DUNNING_DUE,
    /** A sent or open offer expires soon. */
    OFFER_EXPIRING
}
