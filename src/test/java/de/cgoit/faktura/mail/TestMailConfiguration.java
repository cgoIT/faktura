package de.cgoit.faktura.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the SMTP sender by {@link CapturingMailSender} so that no test talks to a mail server.
 */
@TestConfiguration
public class TestMailConfiguration {

    @Bean
    public CapturingMailSender mailSender() {
        return new CapturingMailSender();
    }
}
