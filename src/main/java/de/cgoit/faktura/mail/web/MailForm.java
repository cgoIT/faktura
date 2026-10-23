package de.cgoit.faktura.mail.web;

/**
 * Values of the send dialog as typed by the user.
 */
public record MailForm(String templateId, String recipients, String subject, String body) {

    /**
     * Hides recipients and text from logs.
     */
    @Override
    public String toString() {
        return "MailForm[...]";
    }
}
