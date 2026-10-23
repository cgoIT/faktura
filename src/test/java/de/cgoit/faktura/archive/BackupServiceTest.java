package de.cgoit.faktura.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import de.cgoit.faktura.SqliteTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class BackupServiceTest {

    private static final Path ROOT = Path.of("target/test-archive").toAbsolutePath();
    private static final Path TARGET = Path.of("target/test-backup").toAbsolutePath();

    @Autowired
    private BackupService backups;

    @Autowired
    private ArchiveVerifier verifier;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @TempDir
    Path directory;

    @BeforeEach
    @AfterEach
    void cleanUp() throws IOException {
        jdbc.sql("DELETE FROM file").update();

        if (Files.exists(TARGET)) {
            try (Stream<Path> walk = Files.walk(TARGET)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private ArchivedFile store(String content) {
        var path = "2099/" + UUID.randomUUID() + ".pdf";

        return transaction.execute(status -> archive.store(ArchiveOwnerType.DOCUMENT, "d1", ArchiveFileType.PDF, path,
                content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void backupWritesDumpMirrorAndManifest() throws Exception {
        var file = store("invoice one");

        var result = backups.backup();

        assertThat(result.ok()).isTrue();
        assertThat(result.database()).exists().hasFileName(result.database().getFileName().toString());
        assertThat(result.manifest()).exists();
        assertThat(Files.readString(result.manifest())).contains("databaseSha256").contains("\"checked\" : 1");
        assertThat(TARGET.resolve("archive").resolve(file.path())).hasBinaryContent(
                "invoice one".getBytes(StandardCharsets.UTF_8));
        assertThat(result.newArchiveFiles()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restoredBackupHasTheSameHashesAndAMatchingSchema() throws Exception {
        var first = store("first invoice");
        var second = store("second invoice");
        var result = backups.backup();

        var restored = directory.resolve("restore");
        Files.createDirectories(restored);
        Files.copy(result.database(), restored.resolve("faktura.db"));
        copyTree(TARGET.resolve("archive"), restored.resolve("archive"));

        var url = "jdbc:sqlite:" + restored.resolve("faktura.db");
        var restoredJdbc = JdbcClient.create(new DriverManagerDataSource(url));
        var rows = restoredJdbc.sql("SELECT id, path, size_bytes, sha256 FROM file ORDER BY path")
                .query((rs, n) -> new ArchiveEntry(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .list();

        assertThat(rows).extracting(ArchiveEntry::id).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(rows).extracting(ArchiveEntry::sha256).containsExactlyInAnyOrder(first.sha256(), second.sha256());
        assertThat(verifier.verify(restored.resolve("archive"), rows).ok()).isTrue();

        var flyway = Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
    }

    @Test
    void damagedMirrorMakesTheBackupFailVerification() throws Exception {
        var file = store("good");
        backups.backup();
        Files.writeString(TARGET.resolve("archive").resolve(file.path()), "bad!");

        var dump = latestDump();
        var verification = backups.verifyBackup(TARGET, dump);

        assertThat(verification.ok()).isFalse();
        assertThat(verification.problems()).extracting(ArchiveVerification.Problem::kind)
                .containsExactly(ArchiveVerification.Kind.HASH_MISMATCH);
    }

    @Test
    void runningTwiceNeverOverwritesTheMirror() throws Exception {
        var file = store("keep me");
        backups.backup();

        var mirrored = TARGET.resolve("archive").resolve(file.path());
        Files.writeString(mirrored, "tampered");
        Thread.sleep(1100);

        var second = backups.backup();

        assertThat(mirrored).hasContent("tampered");
        assertThat(second.ok()).isFalse();
    }

    @Test
    void oldDumpsAreDeletedBeyondTheLimitButTheMirrorIsKept() throws Exception {
        var file = store("archive stays");
        Files.createDirectories(TARGET);

        for (var day = 1; day <= 15; day++) {
            var stamp = "202001%02d-000000".formatted(day);
            Files.writeString(TARGET.resolve("faktura-" + stamp + ".db"), "old");
            Files.writeString(TARGET.resolve("manifest-" + stamp + ".json"), "{}");
        }

        backups.backup();

        try (var list = Files.list(TARGET)) {
            var names = list.map(path -> path.getFileName().toString()).toList();

            assertThat(names.stream().filter(name -> name.startsWith("faktura-") && name.endsWith(".db"))).hasSize(14);
            assertThat(names.stream().filter(name -> name.startsWith("manifest-"))).hasSize(14);
            assertThat(names).doesNotContain("faktura-20200101-000000.db", "faktura-20200102-000000.db");
        }

        assertThat(TARGET.resolve("archive").resolve(file.path())).exists();
        assertThat(ROOT.resolve(file.path())).exists();
    }

    @Test
    void failedVerificationKeepsTheOlderDumps() throws Exception {
        var file = store("keep me");
        backups.backup();
        Files.writeString(TARGET.resolve("archive").resolve(file.path()), "tampered");
        Thread.sleep(1100);

        for (var day = 1; day <= 15; day++) {
            Files.writeString(TARGET.resolve("faktura-202001%02d-000000.db".formatted(day)), "old");
        }

        var result = backups.backup();

        assertThat(result.ok()).isFalse();
        assertThat(TARGET.resolve("faktura-20200101-000000.db")).exists();
    }

    @Test
    void partialCopiesOfAnInterruptedRunAreRemoved() throws Exception {
        store("one");
        Files.createDirectories(TARGET.resolve("archive/2099"));
        var partial = TARGET.resolve("archive/2099/half.pdf.partial");
        Files.writeString(partial, "half");

        backups.backup();

        assertThat(partial).doesNotExist();
    }

    private String latestDump() throws IOException {
        try (var list = Files.list(TARGET)) {
            return list.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("faktura-") && name.endsWith(".db"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (var path : walk.toList()) {
                var target = to.resolve(from.relativize(path).toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
