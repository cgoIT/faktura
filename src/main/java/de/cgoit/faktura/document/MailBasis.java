package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.Map;

import de.cgoit.faktura.textblock.Placeholder;

/**
 * What the mail module needs to know about a finalized invoice or cancellation. Everything comes from the snapshots of
 * the document, so a mail names the parties exactly as the document does.
 *
 * @param number         number of the invoice or cancellation
 * @param recipientEmail invoice e-mail address of the customer, may be {@code null}
 * @param contactEmail   e-mail address of the contact person chosen for the document, may be {@code null}
 * @param placeholders   escaped values for the placeholders of the mail text
 */
public record MailBasis(
        String number,
        LocalDate dueDate,
        String recipientEmail,
        String contactEmail,
        Map<Placeholder, String> placeholders) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "MailBasis[number=" + number + "]";
    }
}
