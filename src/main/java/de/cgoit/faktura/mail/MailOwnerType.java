package de.cgoit.faktura.mail;

/**
 * Kind of object a mail is sent about: a finalized invoice or cancellation, or a dunning letter.
 */
public enum MailOwnerType {
    DOCUMENT, DUNNING
}
