package de.cgoit.faktura.mail;

import java.util.Map;

import de.cgoit.faktura.textblock.Placeholder;

/**
 * What the recipient will see: the subject, the HTML part and the plain text part of the mail.
 *
 * @param html   complete HTML document
 * @param values the values the placeholders were replaced with, empty if the text was not expanded
 */
public record MailPreview(String subject, String html, String text, Map<Placeholder, String> values) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "MailPreview[]";
    }
}
