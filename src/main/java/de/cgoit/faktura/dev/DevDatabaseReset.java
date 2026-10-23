package de.cgoit.faktura.dev;

import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Wipes the database before every start of the {@code dev} profile, so the seeder always starts from the same state.
 * Database and archive are only touched if they are clearly development resources; the real database must never be
 * cleaned. Both checks run before anything is deleted.
 */
@Configuration
@Profile("dev")
public class DevDatabaseReset {

    /**
     * A SQLite file named {@code dev*.db}, or a shared in-memory database whose name starts with {@code faktura-dev}.
     * Everything else (other file names, PostgreSQL) is refused.
     */
    private static final Pattern DEV_DATABASE = Pattern.compile(
            "jdbc:sqlite:(?:(?:.*/)?dev[A-Za-z0-9_-]*\\.db|file:faktura-dev[A-Za-z0-9_-]*\\?mode=memory.*)");

    private static final String ARCHIVE_NAME_PREFIX = "dev-archive";

    /**
     * Checks database and archive, then cleans and migrates the schema.
     *
     * @throws IllegalStateException if the database or the archive is not a development resource
     */
    @Bean
    FlywayMigrationStrategy resetThenMigrate(Environment environment) {
        return flyway -> {
            requireDevDatabase(environment.getProperty("spring.datasource.url"));
            requireDevArchive(environment.getProperty("faktura.archive.path"));
            flyway.clean();
            flyway.migrate();
        };
    }

    /**
     * Refuses every database URL that is not a development SQLite database.
     *
     * @throws IllegalStateException if the URL is not accepted
     */
    static void requireDevDatabase(String url) {
        if (url == null || url.contains("..") || !DEV_DATABASE.matcher(url).matches()) {
            throw new IllegalStateException("The dev profile only resets a SQLite database named dev*.db");
        }
    }

    /**
     * Refuses every archive directory whose last path segment does not start with {@code dev-archive}. The path is
     * resolved first, so {@code ..} cannot hide the real directory.
     *
     * @throws IllegalStateException if the path is not accepted
     */
    static void requireDevArchive(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("The dev profile needs an archive path");
        }

        var last = Path.of(path).toAbsolutePath().normalize().getFileName();

        if (last == null || !last.toString().startsWith(ARCHIVE_NAME_PREFIX)) {
            throw new IllegalStateException("The dev profile only empties an archive directory named dev-archive*");
        }
    }
}
