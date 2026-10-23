package de.cgoit.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Compares the schema and the seed rows created by the Flyway migrations on SQLite and PostgreSQL with the checked-in
 * descriptions in {@code src/test/resources/schema}. A deliberate schema change adds a migration and updates the
 * descriptions; run with {@code -Dfaktura.schema.write=true} to rewrite them, then review the diff.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/schema");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void sqliteSchemaMatchesTheDescription() throws Exception {
        var url = "jdbc:sqlite:file:schema-test?mode=memory&cache=shared";

        try (var keeper = DriverManager.getConnection(url);
                var dataSource = new SingleConnectionDataSource(url, true)) {
            migrate(dataSource);

            check("expected-sqlite.txt", SchemaDescriber.describeSqlite(dataSource.getConnection()));
        }
    }

    @Test
    void postgresSchemaMatchesTheDescription() throws Exception {
        try (var dataSource = new SingleConnectionDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), true)) {
            migrate(dataSource);

            check("expected-postgres.txt", SchemaDescriber.describePostgres(dataSource.getConnection()));
        }
    }

    private static void migrate(SingleConnectionDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void check(String file, String actual) throws Exception {
        var path = DIRECTORY.resolve(file);

        if (Boolean.getBoolean("faktura.schema.write")) {
            Files.writeString(path, actual);
        }

        assertThat(actual).isEqualTo(Files.readString(path));
    }
}
