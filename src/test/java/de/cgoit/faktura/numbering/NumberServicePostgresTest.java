package de.cgoit.faktura.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that the schema and the atomic number assignment are portable. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class NumberServicePostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private NumberService numbers;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void assignsConsecutiveNumbersAndRestartsInNewYear() {
        assertThat(next(LocalDate.of(2027, 1, 5))).isEqualTo("RE202700001");
        assertThat(next(LocalDate.of(2027, 1, 6))).isEqualTo("RE202700002");
        assertThat(next(LocalDate.of(2028, 1, 1))).isEqualTo("RE202800001");
    }

    @Test
    void rollbackKeepsNumberFree() {
        var date = LocalDate.of(2030, 2, 1);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            numbers.next(NumberScope.INVOICE, date);
            throw new IllegalStateException("pipeline failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(next(date)).isEqualTo("RE203000001");
    }

    private String next(LocalDate date) {
        return transaction.execute(status -> numbers.next(NumberScope.INVOICE, date));
    }
}
