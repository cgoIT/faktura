package de.cgoit.faktura.mail;

/**
 * The document a mail is about.
 *
 * @param id id of the invoice, cancellation or dunning letter
 */
public record MailTarget(MailOwnerType type, String id) {
}
