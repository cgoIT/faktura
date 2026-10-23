package de.cgoit.faktura.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The dev profile resets the database and fills it with the same invented data every time.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-dev-test?mode=memory&cache=shared",
        "faktura.archive.path=target/dev-archive-test", "faktura.notification.enabled=false" })
@ActiveProfiles("dev")
class DevDataSeederTest {

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void seedingTwiceFromAResetDatabaseGivesTheSameData() {
        var first = snapshot();

        flyway.clean();
        flyway.migrate();
        seeder.seed();

        assertThat(snapshot()).isEqualTo(first);
        assertThat(first).contains("RE202600001", "RE202700001", "RE202700002");
        assertThat(count("customer")).isEqualTo(5);
        assertThat(count("document WHERE status = 'DRAFT'"))
                .isEqualTo(3 + count("recurring_run WHERE status = 'DRAFT'"));
        assertThat(count("document WHERE type = 'OFFER'")).isEqualTo(3);
        assertThat(count("document WHERE type = 'OFFER' AND status = 'FINAL'")).isEqualTo(2);
        assertThat(count("file WHERE type = 'XML' AND owner_id IN (SELECT id FROM document WHERE type = 'OFFER')"))
                .isZero();
        assertThat(count("document WHERE type = 'CANCELLATION'")).isEqualTo(1);
        assertThat(count("file WHERE type = 'XML'")).isPositive();
        assertThat(count("customer WHERE archived = 1")).isEqualTo(1);
        assertThat(count("catalog_item WHERE active = 0")).isEqualTo(1);
        assertThat(count("payment")).isEqualTo(2);
        assertThat(count("dunning_level WHERE (level = 2 AND fee_cents = 250 AND interest_bp = 0)"
                + " OR (level = 3 AND fee_cents = 500 AND interest_bp = 0)")).isEqualTo(2);
        assertThat(count("dunning_level WHERE level = 1 AND fee_cents = 0")).isEqualTo(1);
        assertThat(count("setup_step")).isEqualTo(3);
        assertThat(count("recurring_plan")).isEqualTo(2);
        assertThat(count("recurring_run WHERE status = 'DRAFT'")).isGreaterThanOrEqualTo(2);
        assertThat(count("recurring_run WHERE status = 'RELEASED'")).isZero();
        assertThat(count("notification_setting WHERE draft_enabled = 1 AND plan_error_enabled = 1"
                + " AND overdue_enabled = 1 AND dunning_enabled = 1 AND send_time = '00:00'")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:./data/faktura.db", "jdbc:sqlite:/home/dev/faktura.db",
            "jdbc:postgresql://devbox/faktura", "jdbc:sqlite:./data/dev/../faktura.db", "jdbc:sqlite:./data/dev.db.bak"})
    void databasesThatAreNotDevSqliteFilesAreRefused(String url) {
        assertThatThrownBy(() -> DevDatabaseReset.requireDevDatabase(url)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:./data/dev.db", "jdbc:sqlite:/tmp/dev-local.db",
            "jdbc:sqlite:file:faktura-dev-test?mode=memory&cache=shared"})
    void devDatabasesAreAccepted(String url) {
        DevDatabaseReset.requireDevDatabase(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"./data/archive", "/srv/dev/archive", "~/dev", "./data/dev-archive/../archive", ""})
    void archivesThatAreNotDevArchivesAreRefused(String path) {
        assertThatThrownBy(() -> DevDatabaseReset.requireDevArchive(path)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archiveWithForeignFilesAndNoMarkerIsNotEmptied() throws IOException {
        var directory = Files.createTempDirectory("dev-archive-foreign");
        var file = Files.writeString(directory.resolve("important.pdf"), "keep");
        var guarded = new DevDataSeeder(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                directory.toString());

        assertThatThrownBy(guarded::seed).isInstanceOf(IllegalStateException.class);
        assertThat(file).exists();
    }

    private String snapshot() {
        return String.join(",", jdbc.sql("SELECT number FROM document WHERE number IS NOT NULL ORDER BY number")
                .query(String.class)
                .list());
    }

    private long count(String from) {
        return jdbc.sql("SELECT COUNT(*) FROM " + from)
                .query(Long.class)
                .single();
    }
}
