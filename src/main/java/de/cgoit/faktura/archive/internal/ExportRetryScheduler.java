package de.cgoit.faktura.archive.internal;

import de.cgoit.faktura.archive.ArchiveExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries the export of PDFs that were not copied yet, once a day. Only active with an export directory.
 */
@Component
@ConditionalOnExpression("!'${faktura.archive.export-path:}'.isBlank()")
public class ExportRetryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExportRetryScheduler.class);

    private final ArchiveExportService exports;

    public ExportRetryScheduler(ArchiveExportService exports) {
        this.exports = exports;
    }

    @Scheduled(cron = "${faktura.archive.export-retry-cron:0 45 5 * * *}")
    public void run() {
        try {
            var result = exports.exportMissing();

            if (!result.ok()) {
                LOG.error("Export left {} files open", result.problems().size());
            }
        } catch (RuntimeException e) {
            LOG.error("Export retry failed ({})", e.getClass().getSimpleName());
        }
    }
}
