package de.cgoit.faktura.recurring.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers the settings and switches on scheduling for the daily job.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RecurringProperties.class)
public class RecurringConfiguration {
}
