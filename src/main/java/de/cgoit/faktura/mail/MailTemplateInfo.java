package de.cgoit.faktura.mail;

/**
 * A reusable mail text with placeholders in subject and body.
 *
 * @param isDefault {@code true} if the send dialog starts with this template
 */
public record MailTemplateInfo(String id, MailScope scope, String name, String subject, String body,
        boolean isDefault) {
}
