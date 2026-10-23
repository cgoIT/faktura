package de.cgoit.faktura.notification.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the notification mail ({@code faktura.notification.*}). Which categories are reported and to whom is
 * stored in the database and edited in the application.
 *
 * @param enabled         whether the scheduler runs; the categories themselves are off until the user switches them on
 * @param pollInterval    how often the scheduler checks whether the daily mail is due
 * @param defaultSendTime time of day ({@code HH:mm}) when the user has not chosen one
 * @param retryDelay      wait after a failed mail before the scheduler tries again
 * @param maxFailures     failed runs in a row after which the automatic run pauses
 * @param maxListRows     most items per category in one mail; the rest is reported in a later mail
 */
@ConfigurationProperties("faktura.notification")
public record NotificationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT5M") Duration pollInterval,
        @DefaultValue("08:00") String defaultSendTime,
        @DefaultValue("PT1H") Duration retryDelay,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("20") int maxListRows) {
}
