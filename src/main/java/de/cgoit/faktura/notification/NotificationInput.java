package de.cgoit.faktura.notification;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Editable notification settings. The annotations are the rules for the values; the service checks them.
 *
 * @param offerEnabled report offers that are about to expire
 * @param recipient address of the operator, blank for the e-mail address of the company
 * @param sendTime  time of day as {@code HH:mm}, blank for the configured default
 */
public record NotificationInput(
        boolean draftEnabled,
        boolean planErrorEnabled,
        boolean overdueEnabled,
        boolean dunningEnabled,
        boolean offerEnabled,
        @Size(max = 254, message = "{validation.tooLong}") String recipient,
        @Pattern(regexp = "|([01]\\d|2[0-3]):[0-5]\\d", message = "{notification.error.sendTime}") String sendTime) {

    /**
     * Creates an input with the offer category switched off.
     */
    public NotificationInput(boolean draftEnabled, boolean planErrorEnabled, boolean overdueEnabled,
            boolean dunningEnabled, String recipient, String sendTime) {
        this(draftEnabled, planErrorEnabled, overdueEnabled, dunningEnabled, false, recipient, sendTime);
    }

    /**
     * Hides the recipient from logs.
     */
    @Override
    public String toString() {
        return "NotificationInput[]";
    }
}
