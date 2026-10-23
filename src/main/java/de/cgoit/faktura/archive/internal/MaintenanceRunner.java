package de.cgoit.faktura.archive.internal;

import de.cgoit.faktura.archive.ArchiveExportService;
import de.cgoit.faktura.archive.ArchiveVerification;
import de.cgoit.faktura.archive.ArchiveVerifier;
import de.cgoit.faktura.archive.BackupService;
import de.cgoit.faktura.archive.ExportResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs one maintenance command and then ends the application with an exit code, for use from the command line or a
 * container: {@code --faktura.maintenance=verify-archive} checks the archive against the database,
 * {@code --faktura.maintenance=backup} writes a backup,
 * {@code --faktura.maintenance=export-missing} copies archived PDFs that were not exported yet. Exit code 0 means
 * everything is fine, 1 means a problem was found, 2 means the command failed. Output names files by path only, never content.
 */
@Component
@ConditionalOnProperty(name = "faktura.maintenance")
public class MaintenanceRunner implements ApplicationRunner {

    private static final int EXIT_PROBLEM = 1;
    private static final int EXIT_FAILED = 2;

    private final ConfigurableApplicationContext context;
    private final ArchiveVerifier verifier;
    private final BackupService backups;
    private final ArchiveExportService exports;
    private final String command;

    public MaintenanceRunner(ConfigurableApplicationContext context, ArchiveVerifier verifier, BackupService backups,
            ArchiveExportService exports, @Value("${faktura.maintenance}") String command) {
        this.context = context;
        this.verifier = verifier;
        this.backups = backups;
        this.exports = exports;
        this.command = command;
    }

    @Override
    public void run(ApplicationArguments args) {
        var code = execute();

        System.exit(SpringApplication.exit(context, () -> code));
    }

    int execute() {
        try {
            return switch (command) {
                case "verify-archive" -> report(verifier.verify());
                case "backup" -> backup();
                case "export-missing" -> exportMissing();
                default -> {
                    System.out.println("Unknown command: " + command + " (verify-archive, backup, export-missing)");

                    yield EXIT_FAILED;
                }
            };
        } catch (RuntimeException e) {
            System.out.println("Command failed: " + e.getClass().getSimpleName());

            return EXIT_FAILED;
        }
    }

    private int exportMissing() {
        if (!exports.enabled()) {
            System.out.println("No export directory configured (faktura.archive.export-path)");

            return EXIT_FAILED;
        }

        return report(exports.exportMissing());
    }

    private static int report(ExportResult result) {
        System.out.println("Exported " + result.exported() + " files, " + result.alreadyPresent()
                + " already present, " + result.problems().size() + " problems");

        result.problems().forEach(name -> System.out.println("PROBLEM " + name));

        return result.ok() ? 0 : EXIT_PROBLEM;
    }

    private int backup() {
        var result = backups.backup();

        System.out.println("Backup written: " + result.database().getFileName() + ", " + result.archiveFiles()
                + " archive files, " + result.newArchiveFiles() + " new");

        return result.verification() == null ? 0 : report(result.verification());
    }

    private static int report(ArchiveVerification verification) {
        System.out.println("Checked " + verification.checked() + " archive files, " + verification.problems().size()
                + " problems, " + verification.unknownFiles().size() + " files without database entry");

        verification.problems().forEach(problem -> System.out.println(problem.kind() + " " + problem.path()));

        return verification.ok() ? 0 : EXIT_PROBLEM;
    }
}
