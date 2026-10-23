package de.cgoit.faktura.shared;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.env.Environment;

/**
 * Creates the directory of a SQLite database file before the connection pool opens it. The driver creates the file but
 * not its directory, so a fresh checkout without a {@code data} folder would not start. In-memory databases, URIs with
 * a {@code file:} prefix and other databases are left alone.
 */
public final class DatabaseDirectory {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private DatabaseDirectory() {
    }

    /**
     * Creates the parent directory of the configured SQLite file if it does not exist.
     *
     * @throws UncheckedIOException if the directory cannot be created
     */
    public static void ensure(Environment environment) {
        var url = environment.getProperty("spring.datasource.url", "");

        if (!url.startsWith(SQLITE_PREFIX)) {
            return;
        }

        var location = url.substring(SQLITE_PREFIX.length());
        var query = location.indexOf('?');

        if (query >= 0) {
            location = location.substring(0, query);
        }

        if (location.isBlank() || location.startsWith(":memory:") || location.startsWith("file:")) {
            return;
        }

        var parent = Path.of(location).toAbsolutePath().getParent();

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the database directory " + parent, e);
        }
    }
}
