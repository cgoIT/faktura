package de.cgoit.faktura.notification;

import java.time.LocalDate;

/**
 * The notification settings as stored.
 *
 * @param offerEnabled  report offers that are about to expire
 * @param recipient     address of the operator, {@code null} for the e-mail address of the company
 * @param sendTime      time of day as {@code HH:mm}, {@code null} for the configured default
 * @param lastRunDate   day of the last run that finished without a mail error, {@code null} if there was none
 * @param failureCount  runs in a row that failed to send the mail
 */
public record NotificationSettings(
        boolean draftEnabled,
        boolean planErrorEnabled,
        boolean overdueEnabled,
        boolean dunningEnabled,
        boolean offerEnabled,
        String recipient,
        String sendTime,
        LocalDate lastRunDate,
        int failureCount) {

    /**
     * Creates settings with the offer category switched off.
     */
    public NotificationSettings(boolean draftEnabled, boolean planErrorEnabled, boolean overdueEnabled,
            boolean dunningEnabled, String recipient, String sendTime, LocalDate lastRunDate, int failureCount) {
        this(draftEnabled, planErrorEnabled, overdueEnabled, dunningEnabled, false, recipient, sendTime, lastRunDate,
                failureCount);
    }

    /**
     * Tells whether at least one category is switched on.
     */
    public boolean anyEnabled() {
        return draftEnabled || planErrorEnabled || overdueEnabled || dunningEnabled || offerEnabled;
    }

    /**
     * Hides the recipient from logs.
     */
    @Override
    public String toString() {
        return "NotificationSettings[anyEnabled=" + anyEnabled() + ", failureCount=" + failureCount + "]";
    }
}
