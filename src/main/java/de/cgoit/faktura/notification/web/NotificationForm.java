package de.cgoit.faktura.notification.web;

import de.cgoit.faktura.notification.NotificationInput;
import de.cgoit.faktura.notification.NotificationSettings;

/**
 * Form data of the notification settings; the rules are checked by the service.
 */
public record NotificationForm(Boolean draftEnabled, Boolean planErrorEnabled, Boolean overdueEnabled,
        Boolean dunningEnabled, Boolean offerEnabled, String recipient, String sendTime) {

    static NotificationForm from(NotificationSettings settings) {
        return new NotificationForm(settings.draftEnabled(), settings.planErrorEnabled(), settings.overdueEnabled(),
                settings.dunningEnabled(), settings.offerEnabled(),
                settings.recipient() == null ? "" : settings.recipient(),
                settings.sendTime() == null ? "" : settings.sendTime());
    }

    NotificationInput toInput() {
        return new NotificationInput(Boolean.TRUE.equals(draftEnabled), Boolean.TRUE.equals(planErrorEnabled),
                Boolean.TRUE.equals(overdueEnabled), Boolean.TRUE.equals(dunningEnabled),
                Boolean.TRUE.equals(offerEnabled), recipient, sendTime);
    }

    /**
     * Hides the recipient from logs.
     */
    @Override
    public String toString() {
        return "NotificationForm[]";
    }
}
