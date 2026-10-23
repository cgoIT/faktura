package de.cgoit.faktura.recurring.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.recurring.RunStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of runs. A run records one generated period of a plan; the unique key on plan and period start makes the
 * daily job idempotent.
 */
@Repository
public class RunRepository {

    private static final String SELECT = """
            SELECT id, plan_id, service_from, service_to, status, document_id, created_at
            FROM recurring_run
            """;

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One stored run.
     */
    public record Row(String id, String planId, LocalDate serviceFrom, LocalDate serviceTo, RunStatus status,
            String documentId, String createdAt) {
    }

    public Optional<Row> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(RunRepository::map)
                .optional();
    }

    public List<Row> findByStatus(RunStatus status) {
        return jdbc.sql(SELECT + " WHERE status = :status ORDER BY service_from, id")
                .param("status", status.name())
                .query(RunRepository::map)
                .list();
    }

    public List<Row> findByPlan(String planId) {
        return jdbc.sql(SELECT + " WHERE plan_id = :planId ORDER BY service_from")
                .param("planId", planId)
                .query(RunRepository::map)
                .list();
    }

    public boolean existsForPeriod(String planId, LocalDate serviceFrom) {
        return jdbc.sql("SELECT COUNT(*) FROM recurring_run WHERE plan_id = :planId AND service_from = :from")
                .param("planId", planId)
                .param("from", serviceFrom.toString())
                .query(Long.class)
                .single() > 0;
    }

    public Optional<LocalDate> lastServiceTo(String planId) {
        return jdbc.sql("SELECT MAX(service_to) FROM recurring_run WHERE plan_id = :planId")
                .param("planId", planId)
                .query(String.class)
                .optional()
                .map(LocalDate::parse);
    }

    /**
     * Counts the runs whose draft is waiting for approval; the same runs the approval list shows.
     */
    public long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM recurring_run WHERE status = 'DRAFT' AND document_id IS NOT NULL")
                .query(Long.class)
                .single();
    }

    public void insert(String id, String planId, LocalDate from, LocalDate to, RunStatus status, String documentId,
            String now) {
        jdbc.sql("""
                        INSERT INTO recurring_run (id, plan_id, service_from, service_to, status, document_id, created_at)
                        VALUES (:id, :planId, :from, :to, :status, :documentId, :now)
                        """)
                .param("id", id)
                .param("planId", planId)
                .param("from", from.toString())
                .param("to", to.toString())
                .param("status", status.name())
                .param("documentId", documentId)
                .param("now", now)
                .update();
    }

    /**
     * Marks the run of a deleted draft as skipped and drops the reference to the deleted document.
     */
    public void markSkippedByDocument(String documentId) {
        jdbc.sql("""
                        UPDATE recurring_run SET status = 'SKIPPED', document_id = NULL
                        WHERE document_id = :documentId AND status = 'DRAFT'
                        """)
                .param("documentId", documentId)
                .update();
    }

    public void markReleasedByDocument(String documentId) {
        jdbc.sql("UPDATE recurring_run SET status = 'RELEASED' WHERE document_id = :documentId AND status = 'DRAFT'")
                .param("documentId", documentId)
                .update();
    }

    public void markSkipped(String id) {
        jdbc.sql("UPDATE recurring_run SET status = 'SKIPPED', document_id = NULL WHERE id = :id AND status = 'DRAFT'")
                .param("id", id)
                .update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("plan_id"), LocalDate.parse(rs.getString("service_from")),
                LocalDate.parse(rs.getString("service_to")), RunStatus.valueOf(rs.getString("status")),
                rs.getString("document_id"), rs.getString("created_at"));
    }
}
