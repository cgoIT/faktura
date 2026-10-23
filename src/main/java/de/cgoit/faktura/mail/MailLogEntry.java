package de.cgoit.faktura.mail;

import java.time.LocalDateTime;

/**
 * Proof of one send attempt. It never changes afterwards.
 *
 * @param errorKey message key of the failure reason, {@code null} when sent
 */
public record MailLogEntry(
        String id,
        MailOwnerType ownerType,
        String ownerId,
        String recipients,
        String subject,
        String body,
        String attachmentName,
        boolean sent,
        String errorKey,
        LocalDateTime sentAt) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "MailLogEntry[id=" + id + ", sent=" + sent + "]";
    }
}
