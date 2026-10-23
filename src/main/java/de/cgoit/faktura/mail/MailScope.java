package de.cgoit.faktura.mail;

/**
 * Kind of text a mail template is written for: a document type, or the notification mail to the operator.
 */
public enum MailScope {
    INVOICE, CANCELLATION, DUNNING, OFFER, NOTIFICATION_DIGEST;

    /**
     * Tells whether the template belongs to the application itself: there is exactly one, it cannot be created or
     * deleted and is always the default.
     */
    public boolean system() {
        return this == NOTIFICATION_DIGEST;
    }
}
