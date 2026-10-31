package de.cgoit.faktura.mail;

import de.cgoit.faktura.shared.MailTone;

/**
 * A reusable mail text with placeholders in subject and body.
 *
 * @param isDefault {@code true} if the send dialog starts with this template
 * @param tone       customers with this mail tone get this template, {@code null} for none
 */
public record MailTemplateInfo(String id, MailScope scope, String name, String subject, String body,
        boolean isDefault, MailTone tone) {
}
