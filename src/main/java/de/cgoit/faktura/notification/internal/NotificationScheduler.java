package de.cgoit.faktura.notification.internal;

import java.time.Clock;
import java.time.LocalDateTime;

import de.cgoit.faktura.notification.DigestResult;
import de.cgoit.faktura.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks at a fixed interval whether the daily notification mail is due. Switched off with
 * {@code faktura.notification.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "faktura.notification.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService notifications;
    private final Clock clock;

    public NotificationScheduler(NotificationService notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${faktura.notification.poll-interval:PT5M}",
            initialDelayString = "${faktura.notification.poll-interval:PT5M}")
    void run() {
        try {
            var result = notifications.runIfDue(LocalDateTime.now(clock));

            if (result != null && result.outcome() == DigestResult.Outcome.SENT) {
                LOG.info("Notification run finished: {} ({} items)", result.outcome(), result.count());
            }
        } catch (RuntimeException e) {
            LOG.error("Notification run failed unexpectedly: {}", e.getClass().getSimpleName());
        }
    }
}
