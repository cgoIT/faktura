package de.cgoit.faktura.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import de.cgoit.faktura.archive.internal.ArchiveProperties;
import de.cgoit.faktura.archive.internal.BackupProperties;
import de.cgoit.faktura.shared.RuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backs up the database and the archive together. The SQLite database is written with {@code VACUUM INTO}, which
 * gives a consistent copy while the application keeps running (a plain file copy would not). The archive is mirrored
 * by copying files that are not in the target yet; an existing file is never overwritten and nothing is ever deleted
 * from the archive or its mirror. Only old database dumps and their manifests are removed, as configured, and only
 * after a run whose verification found no problem. After the copy the mirror is verified against the dump, so that a
 * backup that cannot be restored is noticed on the same day.
 * PostgreSQL is backed up with {@code pg_dump} outside the application; this service then refuses to run.
 */
@Service
public class BackupService {

    static final String DUMP_PREFIX = "faktura-";
    static final String DUMP_SUFFIX = ".db";
    static final String MANIFEST_PREFIX = "manifest-";
    static final String ARCHIVE_DIRECTORY = "archive";
    static final String PARTIAL_SUFFIX = ".partial";

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataSource dataSource;
    private final ArchiveProperties archiveProperties;
    private final BackupProperties properties;
    private final ArchiveVerifier verifier;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public BackupService(DataSource dataSource, ArchiveProperties archiveProperties,
            BackupProperties properties, ArchiveVerifier verifier, Clock clock) {
        this.dataSource = dataSource;
        this.archiveProperties = archiveProperties;
        this.properties = properties;
        this.verifier = verifier;
        this.clock = clock;
    }

    /**
     * Runs a backup into the configured directory.
     *
     * @throws RuleViolationException if the database is not SQLite
     */
    public synchronized BackupResult backup() {
        requireSqlite();

        var target = Path.of(properties.path()).toAbsolutePath().normalize();
        var now = Instant.now(clock);
        var stamp = STAMP.format(now.atZone(clock.getZone()));

        try {
            Files.createDirectories(target.resolve(ARCHIVE_DIRECTORY));

            var dump = dumpDatabase(target, stamp);
            var copied = mirrorArchive(target.resolve(ARCHIVE_DIRECTORY));
            var total = countFiles(target.resolve(ARCHIVE_DIRECTORY));
            var verification = properties.verify() ? verifyBackup(target, dump.getFileName().toString()) : null;
            var manifest = writeManifest(target, stamp, now, dump, total, copied, verification);

            if (verification == null || verification.ok()) {
                prune(target);
            }

            var result = new BackupResult(now, dump, manifest, total, copied, verification);

            LOG.info("Backup written: {} archive files, {} new, verification {}", total, copied,
                    verification == null ? "off" : verification.ok() ? "ok" : "FAILED");

            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Backup failed", e);
        }
    }

    /**
     * Verifies the archive mirror in a backup directory against the database dump in the same directory.
     */
    public ArchiveVerification verifyBackup(Path backupDirectory, String dumpName) {
        var dump = backupDirectory.resolve(dumpName);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:file:" + dump + "?mode=ro");
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id, path, size_bytes, sha256 FROM file ORDER BY path")) {
            var entries = new ArrayList<ArchiveEntry>();

            while (rows.next()) {
                entries.add(new ArchiveEntry(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4)));
            }

            return verifier.verify(backupDirectory.resolve(ARCHIVE_DIRECTORY), entries);
        } catch (SQLException e) {
            throw new IllegalStateException("Backup database cannot be read", e);
        }
    }

    private void requireSqlite() {
        try (var connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("sqlite")) {
                throw new RuleViolationException("backup.error.notSqlite");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database not reachable", e);
        }
    }

    private Path dumpDatabase(Path target, String stamp) throws IOException {
        var name = DUMP_PREFIX + stamp + DUMP_SUFFIX;
        var finalFile = target.resolve(name);

        if (Files.exists(finalFile)) {
            throw new IllegalStateException("Backup file already exists: " + name);
        }

        var temporary = target.resolve(name + ".tmp");
        Files.deleteIfExists(temporary);

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, temporary.toString());
            statement.execute();
        } catch (SQLException e) {
            Files.deleteIfExists(temporary);

            throw new IllegalStateException("Database dump failed", e);
        }

        Files.move(temporary, finalFile, StandardCopyOption.ATOMIC_MOVE);

        return finalFile;
    }

    /**
     * Copies archive files that the mirror does not have. Existing mirror files are left alone.
     */
    private int mirrorArchive(Path mirror) throws IOException {
        var source = Path.of(archiveProperties.path()).toAbsolutePath().normalize();

        if (Files.notExists(source)) {
            return 0;
        }

        removePartialCopies(mirror);

        var copied = 0;

        try (var walk = Files.walk(source)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var relative = source.relativize(file);

                if (relative.startsWith(ArchiveService.STAGING_DIRECTORY)) {
                    continue;
                }

                var copy = mirror.resolve(relative.toString());

                if (Files.exists(copy)) {
                    continue;
                }

                Files.createDirectories(copy.getParent());

                var partial = copy.resolveSibling(copy.getFileName() + PARTIAL_SUFFIX);
                Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, copy, StandardCopyOption.ATOMIC_MOVE);
                copied++;
            }
        }

        return copied;
    }

    /**
     * Removes copies that an interrupted run left behind. They are never archive files, only half-written copies.
     */
    private static void removePartialCopies(Path mirror) throws IOException {
        try (var walk = Files.walk(mirror)) {
            for (var path : walk.filter(p -> p.getFileName().toString().endsWith(PARTIAL_SUFFIX)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int countFiles(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private Path writeManifest(Path target, String stamp, Instant now, Path dump, int total, int copied,
            ArchiveVerification verification) throws IOException {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("createdAt", now.toString());
        manifest.put("database", dump.getFileName().toString());
        manifest.put("databaseSha256", ArchiveVerifier.sha256(dump));
        manifest.put("archiveFiles", total);
        manifest.put("newArchiveFiles", copied);

        if (verification != null) {
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("checked", verification.checked());
            checked.put("problems", verification.problems().stream()
                    .map(problem -> problem.path() + ": " + problem.kind())
                    .toList());
            checked.put("unknownFiles", verification.unknownFiles().size());
            manifest.put("verification", checked);
        }

        var file = target.resolve(MANIFEST_PREFIX + stamp + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

        return file;
    }

    /**
     * Deletes database dumps and manifests beyond the configured number. The archive mirror is not touched.
     */
    private void prune(Path target) throws IOException {
        var keep = Math.max(properties.keepCount(), 1);
        List<Path> dumps;

        try (var list = Files.list(target)) {
            dumps = list.filter(path -> path.getFileName().toString().startsWith(DUMP_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(DUMP_SUFFIX))
                    .sorted()
                    .toList();
        }

        for (var old : dumps.subList(0, Math.max(dumps.size() - keep, 0))) {
            var stamp = old.getFileName().toString()
                    .substring(DUMP_PREFIX.length(), old.getFileName().toString().length() - DUMP_SUFFIX.length());

            Files.deleteIfExists(old);
            Files.deleteIfExists(target.resolve(MANIFEST_PREFIX + stamp + ".json"));
        }
    }
}
