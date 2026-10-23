package de.cgoit.faktura;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Empties the throw-away archive of the tests. Tests issue the same document numbers again and again; production code
 * never deletes archive files.
 */
public final class TestArchive {

    private static final Path ROOT = Path.of("target/test-archive");

    private TestArchive() {
    }

    public static void clear() {
        for (var year : List.of("2026", "2027", "dunning")) {
            var directory = ROOT.resolve(year);

            if (Files.notExists(directory)) {
                continue;
            }

            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
