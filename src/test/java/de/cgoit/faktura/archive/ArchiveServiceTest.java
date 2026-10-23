package de.cgoit.faktura.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class ArchiveServiceTest {

    private static final Path ROOT = Path.of("target/test-archive").toAbsolutePath();
    private static final byte[] CONTENT = "%PDF test content".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ArchiveService archive;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM file").update();
    }

    @Test
    void storedFileAppearsAtItsPathOnlyAfterCommit() {
        var path = "2099/" + UUID.randomUUID() + ".pdf";

        var file = transaction.execute(status -> {
            var stored = archive.store(ArchiveOwnerType.DOCUMENT, "d1", ArchiveFileType.PDF, path, CONTENT);

            assertThat(ROOT.resolve(path)).doesNotExist();
            assertThat(ROOT.resolve(".staging").resolve(stored.id() + ".tmp")).exists();

            return stored;
        });

        assertThat(ROOT.resolve(path)).hasBinaryContent(CONTENT);
        assertThat(ROOT.resolve(".staging").resolve(file.id() + ".tmp")).doesNotExist();
        assertThat(file.sha256()).hasSize(64);
        assertThat(file.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(archive.read(file)).isEqualTo(CONTENT);
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, "d1")).containsExactly(file);
    }

    @Test
    void rollbackRemovesTheStagedFileAndTheRow() {
        var path = "2099/" + UUID.randomUUID() + ".pdf";

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            archive.store(ArchiveOwnerType.DOCUMENT, "d2", ArchiveFileType.PDF, path, CONTENT);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ROOT.resolve(path)).doesNotExist();
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, "d2")).isEmpty();
        assertThat(stagedFiles()).isZero();
    }

    @Test
    void changedFileIsDetectedWhenRead() throws IOException {
        var path = "2099/" + UUID.randomUUID() + ".pdf";
        var file = transaction.execute(status ->
                archive.store(ArchiveOwnerType.DOCUMENT, "d3", ArchiveFileType.PDF, path, CONTENT));

        Files.write(ROOT.resolve(path), "tampered".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> archive.read(file)).isInstanceOf(ArchiveIntegrityException.class);
    }

    @Test
    void storingWithoutTransactionFails() {
        assertThatThrownBy(() -> archive.store(ArchiveOwnerType.DOCUMENT, "d4", ArchiveFileType.PDF, "2099/x.pdf",
                CONTENT)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unsafePathsAreRejected() {
        for (var path : new String[] {"../escape.pdf", "/abs.pdf", "a/../b.pdf", "a//b.pdf", ".hidden.pdf", ""}) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    archive.store(ArchiveOwnerType.DOCUMENT, "d5", ArchiveFileType.PDF, path, CONTENT)))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void stagedFileWithCommittedRowIsCompletedOnRead() throws IOException {
        var path = "2099/" + UUID.randomUUID() + ".pdf";
        var file = transaction.execute(status ->
                archive.store(ArchiveOwnerType.DOCUMENT, "d6", ArchiveFileType.PDF, path, CONTENT));

        // simulate a crash between commit and move: put the file back into staging
        var staged = ROOT.resolve(".staging").resolve(file.id() + ".tmp");
        Files.move(ROOT.resolve(path), staged);

        assertThat(archive.read(file)).isEqualTo(CONTENT);
        assertThat(ROOT.resolve(path)).exists();
    }

    @Test
    void startupCompletesCommittedAndRemovesOrphanedStagedFiles() throws IOException {
        var path = "2099/" + UUID.randomUUID() + ".pdf";
        var file = transaction.execute(status ->
                archive.store(ArchiveOwnerType.DOCUMENT, "d7", ArchiveFileType.PDF, path, CONTENT));
        var committed = ROOT.resolve(".staging").resolve(file.id() + ".tmp");
        Files.move(ROOT.resolve(path), committed);
        var orphan = ROOT.resolve(".staging").resolve(UUID.randomUUID() + ".tmp");
        Files.write(orphan, CONTENT);

        archive.recoverStaged();

        assertThat(ROOT.resolve(path)).hasBinaryContent(CONTENT);
        assertThat(orphan).doesNotExist();
    }

    private long stagedFiles() {
        try (var files = Files.list(ROOT.resolve(".staging"))) {
            return files.count();
        } catch (IOException e) {
            return 0;
        }
    }
}
