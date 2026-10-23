package de.cgoit.faktura.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import de.cgoit.faktura.archive.internal.ExportFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExportFilesTest {

    private static final byte[] CONTENT = "%PDF content".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = ArchiveService.sha256(CONTENT);

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({ "2027/RE202700001.pdf,RE202700001.pdf", "dunning/2027/MA202700003.pdf,MA202700003.pdf",
            "RE-1_a.pdf,RE-1_a.pdf" })
    void nameIsTheLastSegmentOfTheArchivePath(String path, String expected) {
        assertThat(ExportFiles.exportName(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "2027/.hidden.pdf", "2027/a b.pdf", "2027/", "2027/..", "2027/a;b.pdf", "" })
    void unsafeNamesAreRejected(String path) {
        assertThatThrownBy(() -> ExportFiles.exportName(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesAFlatCopyWithoutLeavingPartFiles() throws Exception {
        var outcome = ExportFiles.write(directory, "RE1.pdf", CONTENT, HASH);

        assertThat(outcome).isEqualTo(ExportFiles.Outcome.WRITTEN);
        assertThat(directory.resolve("RE1.pdf")).hasBinaryContent(CONTENT);
        assertThat(directory).isDirectoryContaining(path -> path.getFileName().toString().equals("RE1.pdf"));

        try (var list = Files.list(directory)) {
            assertThat(list.count()).isEqualTo(1);
        }
    }

    @Test
    void existingFileWithSameContentIsLeftAlone() throws Exception {
        Files.write(directory.resolve("RE1.pdf"), CONTENT);

        assertThat(ExportFiles.write(directory, "RE1.pdf", CONTENT, HASH))
                .isEqualTo(ExportFiles.Outcome.ALREADY_PRESENT);
    }

    @Test
    void existingFileWithOtherContentIsNeverOverwritten() throws Exception {
        Files.write(directory.resolve("RE1.pdf"), "other".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ExportFiles.write(directory, "RE1.pdf", CONTENT, HASH))
                .isInstanceOf(ExportFiles.ConflictException.class);
        assertThat(directory.resolve("RE1.pdf")).hasContent("other");
    }

    @Test
    void wrongHashAbortsAndRemovesThePartFile() throws Exception {
        assertThatThrownBy(() -> ExportFiles.write(directory, "RE1.pdf", CONTENT, "0".repeat(64)))
                .isInstanceOf(IOException.class);

        try (var list = Files.list(directory)) {
            assertThat(list.count()).isZero();
        }
    }

    @Test
    void missingDirectoryIsAnErrorAndNotCreated() {
        var missing = directory.resolve("nope");

        assertThatThrownBy(() -> ExportFiles.write(missing, "RE1.pdf", CONTENT, HASH))
                .isInstanceOf(IOException.class);
        assertThat(missing).doesNotExist();
    }

    @Test
    void pathTraversalInTheNameIsRejected() {
        assertThatThrownBy(() -> ExportFiles.write(directory, "../x.pdf", CONTENT, HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
