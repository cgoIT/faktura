package de.cgoit.faktura.home.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the manually ticked set-up steps.
 */
@Repository
public class SetupStepRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    public SetupStepRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Set<String> findDone() {
        return Set.copyOf(jdbc.sql("SELECT step FROM setup_step")
                .query(String.class)
                .list());
    }

    public void markDone(String step) {
        markOpen(step);
        jdbc.sql("INSERT INTO setup_step (step, done_at) VALUES (:step, :doneAt)")
                .param("step", step)
                .param("doneAt", Instant.now(clock).toString())
                .update();
    }

    public void markOpen(String step) {
        jdbc.sql("DELETE FROM setup_step WHERE step = :step")
                .param("step", step)
                .update();
    }
}
