package de.cgoit.faktura.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Source scan: the export code never deletes archive or export files, except the {@code .part} file of its own
 * failed attempt.
 */
class ExportSourceTest {

    private static final Path BASE = Path.of("src/main/java/de/cgoit/faktura/archive");
    private static final Pattern DELETE = Pattern.compile("Files\\.(delete|deleteIfExists|walk)\\b|\\.delete\\(\\)");

    @Test
    void exportServiceSchedulerAndRepositoryDoNotDeleteFiles() throws IOException {
        for (var file : new String[] { "ArchiveExportService.java", "internal/ExportRetryScheduler.java",
                "internal/FileExportRepository.java" }) {
            assertThat(DELETE.matcher(Files.readString(BASE.resolve(file))).find()).as(file).isFalse();
        }
    }

    @Test
    void exportFilesDeletesOnlyItsOwnPartFile() throws IOException {
        var source = Files.readString(BASE.resolve("internal/ExportFiles.java"));
        var matcher = DELETE.matcher(source);
        var count = 0;

        while (matcher.find()) {
            count++;
        }

        assertThat(count).isEqualTo(1);
        assertThat(source).contains("Files.deleteIfExists(part)");
    }
}
