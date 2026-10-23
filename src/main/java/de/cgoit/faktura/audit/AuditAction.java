package de.cgoit.faktura.audit;

/**
 * Kind of event written to the audit log.
 */
public enum AuditAction {
    FINALIZE, CANCEL, PAYMENT_RECORD, PAYMENT_REVERSE, DUNNING_CREATE, MAIL_SEND, OFFER_EVENT
}
