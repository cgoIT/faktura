package de.cgoit.faktura.numbering.internal;

import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.numbering.NumberScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of number patterns and counters.
 */
@Repository
public class NumberSequenceRepository {

    private final JdbcClient jdbc;

    public NumberSequenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findPattern(NumberScope scope) {
        return jdbc.sql("SELECT pattern FROM number_pattern WHERE scope = :scope")
                .param("scope", scope.name())
                .query(String.class)
                .optional();
    }

    public void updatePattern(NumberScope scope, String pattern) {
        jdbc.sql("UPDATE number_pattern SET pattern = :pattern WHERE scope = :scope")
                .param("pattern", pattern)
                .param("scope", scope.name())
                .update();
    }

    /**
     * Returns the last assigned counter value of the period, or 0 if none was assigned yet.
     */
    public long lastValue(NumberScope scope, String period) {
        return jdbc.sql("SELECT last_value FROM number_sequence WHERE scope = :scope AND period = :period")
                .param("scope", scope.name())
                .param("period", period)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /**
     * Tells whether any number was already assigned in the scope.
     */
    public boolean hasAssignedNumbers(NumberScope scope) {
        var count = jdbc.sql("SELECT COUNT(*) FROM number_sequence WHERE scope = :scope AND last_value > 0")
                .param("scope", scope.name())
                .query(Long.class)
                .single();

        return count > 0;
    }

    /**
     * Increments the counter of the period with a single statement and returns the new value.
     * Creates the counter row on first use. Must run in the transaction of the caller.
     */
    public long increment(NumberScope scope, String period) {
        jdbc.sql("""
                        INSERT INTO number_sequence (id, scope, period, last_value)
                        VALUES (:id, :scope, :period, 0)
                        ON CONFLICT (scope, period) DO NOTHING
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("scope", scope.name())
                .param("period", period)
                .update();

        return jdbc.sql("""
                        UPDATE number_sequence SET last_value = last_value + 1
                        WHERE scope = :scope AND period = :period
                        RETURNING last_value
                        """)
                .param("scope", scope.name())
                .param("period", period)
                .query(Long.class)
                .single();
    }
}
