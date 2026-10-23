package de.cgoit.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies that Flyway creates the schema on SQLite and the default units exist.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:migration-test?mode=memory&cache=shared")
class MigrationTests {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void defaultUnitsAreCreated() {
        var count = jdbc.sql("SELECT COUNT(*) FROM unit").query(Integer.class).single();

        assertThat(count).isEqualTo(5);
    }
}
