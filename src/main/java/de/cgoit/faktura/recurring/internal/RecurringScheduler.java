package de.cgoit.faktura.recurring.internal;

import java.time.Clock;
import java.time.LocalDate;

import de.cgoit.faktura.recurring.RecurringJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts the daily job on the schedule from {@code faktura.recurring.cron}. Switched off with
 * {@code faktura.recurring.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "faktura.recurring.enabled", havingValue = "true")
public class RecurringScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringScheduler.class);

    private final RecurringJobService job;
    private final Clock clock;

    public RecurringScheduler(RecurringJobService job, Clock clock) {
        this.job = job;
        this.clock = clock;
    }

    @Scheduled(cron = "${faktura.recurring.cron}")
    void run() {
        var created = job.runDue(LocalDate.now(clock));

        LOG.info("Recurring job finished, {} drafts created", created);
    }
}
