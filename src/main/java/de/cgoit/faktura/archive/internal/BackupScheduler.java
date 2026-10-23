package de.cgoit.faktura.archive.internal;

import de.cgoit.faktura.archive.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the backup on the configured schedule. A failed backup is logged as an error and tried again at the next run.
 */
@Component
@ConditionalOnProperty(name = "faktura.backup.enabled", havingValue = "true")
public class BackupScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backups;

    public BackupScheduler(BackupService backups) {
        this.backups = backups;
    }

    @Scheduled(cron = "${faktura.backup.cron}")
    public void run() {
        try {
            var result = backups.backup();

            if (!result.ok()) {
                LOG.error("Backup verification found problems, see the manifest {}", result.manifest().getFileName());
            }
        } catch (RuntimeException e) {
            LOG.error("Backup failed ({})", e.getClass().getSimpleName());
        }
    }
}
