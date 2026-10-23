package de.cgoit.faktura.archive;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.cgoit.faktura.archive.internal.ArchiveProperties;
import de.cgoit.faktura.archive.internal.ExportFiles;
import de.cgoit.faktura.archive.internal.FileExportRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Copies archived PDFs into the optional export directory (for example a folder watched by ecoDMS), flat and named
 * like the archive file. The archive stays the source of truth: copies are made from the hash-verified archive file,
 * never overwrite anything and are never removed. Which PDF was copied is remembered in {@code file_export}, because
 * the watching program may delete a copy after importing it. A failure never affects the document; the PDF stays
 * open and is retried at startup, by the daily job and by the {@code export-missing} command.
 */
@Service
public class ArchiveExportService {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveExportService.class);

    private static final Duration GRACE = Duration.ofMinutes(10);

    private final ArchiveService archive;
    private final FileExportRepository exports;
    private final ArchiveProperties properties;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "archive-export");

        thread.setDaemon(true);

        return thread;
    });

    public ArchiveExportService(ArchiveService archive, FileExportRepository exports,
            ArchiveProperties properties, Clock clock) {
        this.archive = archive;
        this.exports = exports;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.exportEnabled();
    }

    /**
     * Reports the number of PDFs that are still not copied after a grace period of ten minutes.
     */
    public ExportStatus status() {
        return new ExportStatus(enabled(), enabled() ? exports.countPendingUntil(Instant.now(clock).minus(GRACE)) : 0);
    }

    /**
     * Schedules an export run in the background; used after a document was committed. Does nothing without an export
     * directory.
     */
    public void requestExport() {
        if (!enabled()) {
            return;
        }

        executor.execute(() -> {
            try {
                exportMissing();
            } catch (RuntimeException e) {
                LOG.error("Export run failed ({})", e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Copies every archived PDF without an export marker. A file that already exists in the export directory with
     * the expected content is only remembered; one with different content is reported as a problem and left alone.
     * Runs one at a time. Does nothing without an export directory.
     */
    public synchronized ExportResult exportMissing() {
        var problems = new ArrayList<String>();
        var exported = 0;
        var present = 0;

        if (!enabled()) {
            return new ExportResult(0, 0, problems);
        }

        var directory = Path.of(properties.exportPath());

        for (var file : exports.findPending()) {
            var name = file.path();

            try {
                name = ExportFiles.exportName(file.path());

                var outcome = ExportFiles.write(directory, name, archive.read(file), file.sha256());

                exports.mark(file.id(), file.sha256(), Instant.now(clock));

                if (outcome == ExportFiles.Outcome.WRITTEN) {
                    exported++;
                } else {
                    present++;
                }
            } catch (Exception e) {
                problems.add(name);
                LOG.error("Export of {} failed ({})", name, e.getClass().getSimpleName());
            }
        }

        return new ExportResult(exported, present, List.copyOf(problems));
    }

    @EventListener(ApplicationReadyEvent.class)
    void exportAtStartup() {
        requestExport();
    }

    /**
     * Waits until the background runs queued so far are done.
     */
    void awaitIdle() throws Exception {
        executor.submit(() -> { })
                .get(30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
