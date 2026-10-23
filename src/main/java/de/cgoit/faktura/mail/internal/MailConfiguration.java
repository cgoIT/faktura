package de.cgoit.faktura.mail.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the mail configuration properties.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {
}
