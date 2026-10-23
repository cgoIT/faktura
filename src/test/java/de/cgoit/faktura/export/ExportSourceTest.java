package de.cgoit.faktura.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ExportSourceTest {

    @Test
    void exportCodeUsesNoFloatingPointAndNoWrites() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java/de/cgoit/faktura/export"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);

                assertThat(source).as(file.toString()).doesNotContain("double ").doesNotContain("float ")
                        .doesNotContain("@PostMapping").doesNotContain("INSERT ").doesNotContain("UPDATE ")
                        .doesNotContain("DELETE ");
            }
        }
    }
}
