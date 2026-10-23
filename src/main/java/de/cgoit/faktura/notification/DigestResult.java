package de.cgoit.faktura.notification;

/**
 * Result of a run of the notification mail.
 *
 * @param outcome  what happened
 * @param count    items reported in the mail, 0 unless the mail was sent
 * @param errorKey message key of the reason for {@code FAILED} and {@code BLOCKED}, otherwise {@code null}
 */
public record DigestResult(Outcome outcome, int count, String errorKey) {

    /**
     * What a run did.
     */
    public enum Outcome {
        /** The mail was sent and the items are logged. */
        SENT,
        /** Nothing new to report, no mail. */
        NOTHING_TO_REPORT,
        /** No category is switched on. */
        DISABLED,
        /** The automatic run is paused after repeated failures. */
        PAUSED,
        /** Mail cannot be sent (not set up, no recipient); not counted as a failure. */
        BLOCKED,
        /** The mail was not accepted; the items stay unreported and the next run tries again. */
        FAILED
    }

    static DigestResult of(Outcome outcome) {
        return new DigestResult(outcome, 0, null);
    }
}
