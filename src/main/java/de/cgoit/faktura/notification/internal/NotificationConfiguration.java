package de.cgoit.faktura.notification.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers the settings and switches on scheduling for the daily notification mail.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {
}
