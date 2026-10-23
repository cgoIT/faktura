package de.cgoit.faktura.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class ArchiveVerifierTest {

    private static final Path ROOT = Path.of("target/test-archive").toAbsolutePath();

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
    void cleanUp() {
        jdbc.sql("DELETE FROM file").update();
    }

    private ArchivedFile store(String content) {
        var path = "2099/" + UUID.randomUUID() + ".pdf";

        return transaction.execute(status -> archive.store(ArchiveOwnerType.DOCUMENT, "d1", ArchiveFileType.PDF, path,
                content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void intactArchiveVerifies() {
        store("one");
        store("two");

        var result = verifier.verify();

        assertThat(result.ok()).isTrue();
        assertThat(result.checked()).isEqualTo(2);
    }

    @Test
    void missingFileIsReported() throws Exception {
        var file = store("one");
        Files.delete(ROOT.resolve(file.path()));

        var result = verifier.verify();

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).containsExactly(
                new ArchiveVerification.Problem(file.id(), file.path(), ArchiveVerification.Kind.MISSING));
    }

    @Test
    void changedFileIsReportedAsHashMismatchOrSizeMismatch() throws Exception {
        var same = store("abcd");
        var longer = store("abcd");
        Files.writeString(ROOT.resolve(same.path()), "abce");
        Files.writeString(ROOT.resolve(longer.path()), "abcdef");

        var result = verifier.verify();

        assertThat(result.problems()).extracting(ArchiveVerification.Problem::kind)
                .containsExactlyInAnyOrder(ArchiveVerification.Kind.HASH_MISMATCH,
                        ArchiveVerification.Kind.SIZE_MISMATCH);
    }

    @Test
    void filesWithoutEntryAreListedButDoNotFail() throws Exception {
        var file = store("one");
        var stray = ROOT.resolve("2099/stray-" + UUID.randomUUID() + ".pdf");
        Files.writeString(stray, "stray");

        try {
            var result = verifier.verify();

            assertThat(result.ok()).isTrue();
            assertThat(result.unknownFiles()).contains("2099/" + stray.getFileName());
            assertThat(result.unknownFiles()).doesNotContain(file.path());
        } finally {
            Files.deleteIfExists(stray);
        }
    }

    @Test
    void entriesCannotPointOutsideTheRoot() throws Exception {
        Files.writeString(directory.resolve("secret.txt"), "x");

        var result = verifier.verify(directory.resolve("root"),
                List.of(new ArchiveEntry("e1", "../secret.txt", 1, "0".repeat(64))));

        assertThat(result.problems()).extracting(ArchiveVerification.Problem::kind)
                .containsExactly(ArchiveVerification.Kind.MISSING);
    }
}
