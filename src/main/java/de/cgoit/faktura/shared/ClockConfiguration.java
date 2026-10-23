package de.cgoit.faktura.shared;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the system clock as a bean so that date-dependent logic can be tested with a fixed clock.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
