package de.cgoit.faktura.mail;

/**
 * Whether documents can be sent by mail at the moment.
 *
 * @param reasonKey      message key of the reason why not, {@code null} if sending is possible
 * @param maxAttachmentBytes larger attachments are not sent
 */
public record MailStatus(boolean available, String reasonKey, long maxAttachmentBytes) {
}
