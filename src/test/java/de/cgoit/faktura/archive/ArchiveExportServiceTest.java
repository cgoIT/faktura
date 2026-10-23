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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
@TestPropertySource(properties = "faktura.archive.export-path=target/test-export")
class ArchiveExportServiceTest {

    private static final Path EXPORT = Path.of("target/test-export").toAbsolutePath();
    private static final byte[] CONTENT = "%PDF export content".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ArchiveExportService exports;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        exports.awaitIdle();
        jdbc.sql("DELETE FROM file_export").update();
        jdbc.sql("DELETE FROM file").update();

        if (Files.exists(EXPORT)) {
            try (Stream<Path> walk = Files.walk(EXPORT)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }

        Files.createDirectories(EXPORT);
    }

    @Test
    void committedPdfIsCopiedFlatWithItsArchiveHash() throws Exception {
        var name = unique();
        var file = store("2099/" + name, ArchiveFileType.PDF);

        exports.awaitIdle();

        assertThat(EXPORT.resolve(name)).hasBinaryContent(CONTENT);
        assertThat(ArchiveVerifier.sha256(EXPORT.resolve(name))).isEqualTo(file.sha256());
        assertThat(pending()).isZero();
    }

    @Test
    void dunningPathIsFlattened() throws Exception {
        var name = unique();

        store("dunning/2099/" + name, ArchiveFileType.PDF);
        exports.awaitIdle();

        assertThat(EXPORT.resolve(name)).exists();
        assertThat(EXPORT.resolve("dunning")).doesNotExist();
    }

    @Test
    void xmlIsNotExported() throws Exception {
        store("2099/" + unique().replace(".pdf", ".xml"), ArchiveFileType.XML);
        exports.awaitIdle();

        try (var list = Files.list(EXPORT)) {
            assertThat(list.count()).isZero();
        }
    }

    @Test
    void rolledBackDocumentIsNotExported() throws Exception {
        try {
            transaction.executeWithoutResult(status -> {
                archive.store(ArchiveOwnerType.DOCUMENT, "d", ArchiveFileType.PDF, "2099/" + unique(), CONTENT);
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        exports.awaitIdle();

        try (var list = Files.list(EXPORT)) {
            assertThat(list.count()).isZero();
        }
    }

    @Test
    void failingExportKeepsTheDocumentAndStaysOpenUntilRetried() throws Exception {
        Files.delete(EXPORT);
        var name = unique();
        var file = store("2099/" + name, ArchiveFileType.PDF);

        exports.awaitIdle();

        assertThat(archive.findById(file.id())).isPresent();
        assertThat(archive.read(file)).isEqualTo(CONTENT);
        assertThat(pending()).isEqualTo(1);

        Files.createDirectories(EXPORT);
        var result = exports.exportMissing();

        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.ok()).isTrue();
        assertThat(EXPORT.resolve(name)).hasBinaryContent(CONTENT);
        assertThat(pending()).isZero();
    }

    @Test
    void runIsIdempotentAndDoesNotRecreateAFileThatTheReceiverRemoved() throws Exception {
        var name = unique();

        store("2099/" + name, ArchiveFileType.PDF);
        exports.awaitIdle();
        Files.delete(EXPORT.resolve(name));

        var result = exports.exportMissing();

        assertThat(result.exported()).isZero();
        assertThat(EXPORT.resolve(name)).doesNotExist();
    }

    @Test
    void existingFileWithSameContentIsOnlyRemembered() throws Exception {
        var name = unique();
        Files.write(EXPORT.resolve(name), CONTENT);
        store("2099/" + name, ArchiveFileType.PDF);
        exports.awaitIdle();

        assertThat(pending()).isZero();
    }

    @Test
    void existingFileWithOtherContentIsReportedAndKept() throws Exception {
        var name = unique();
        Files.write(EXPORT.resolve(name), "foreign".getBytes(StandardCharsets.UTF_8));
        store("2099/" + name, ArchiveFileType.PDF);
        exports.awaitIdle();

        var result = exports.exportMissing();

        assertThat(result.problems()).containsExactly(name);
        assertThat(EXPORT.resolve(name)).hasContent("foreign");
        assertThat(pending()).isEqualTo(1);
    }

    private long pending() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM file f
                WHERE f.type = 'PDF' AND NOT EXISTS (SELECT 1 FROM file_export e WHERE e.file_id = f.id)
                """)
                .query(Long.class)
                .single();
    }

    @Test
    void statusIgnoresPdfsThatAreStillWithinTheGracePeriod() throws Exception {
        Files.delete(EXPORT);
        store("2099/" + unique(), ArchiveFileType.PDF);
        exports.awaitIdle();

        assertThat(pending()).isEqualTo(1);
        assertThat(exports.status().open()).isZero();
    }

    private ArchivedFile store(String path, ArchiveFileType type) {
        return transaction.execute(status ->
                archive.store(ArchiveOwnerType.DOCUMENT, "d-" + UUID.randomUUID(), type, path, CONTENT));
    }

    private static String unique() {
        return "T" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
    }
}
