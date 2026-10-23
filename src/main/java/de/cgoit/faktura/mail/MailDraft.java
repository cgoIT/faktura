package de.cgoit.faktura.mail;

import java.util.Optional;

/**
 * The prefilled send dialog of a document: recipient, text from the chosen template and the attachment.
 *
 * @param number          number of the document the mail is about
 * @param recipients      prefilled addresses, comma separated, may be empty
 * @param template        template the text comes from, empty without any template
 * @param subject         subject with placeholders filled in
 * @param body            text with placeholders filled in; the user may edit it, it is sent as it stands
 * @param attachmentName  file name the PDF is sent under
 * @param attachmentBytes size of the PDF
 * @param blockedKey      message key of the reason why the mail cannot be sent, {@code null} if it can
 * @param lastSent        the latest successful send of this document
 */
public record MailDraft(
        MailTarget target,
        MailScope scope,
        String number,
        String recipients,
        Optional<MailTemplateInfo> template,
        String subject,
        String body,
        String attachmentName,
        long attachmentBytes,
        String blockedKey,
        Optional<MailLogEntry> lastSent) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "MailDraft[number=" + number + "]";
    }
}
