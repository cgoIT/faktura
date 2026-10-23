package de.cgoit.faktura.audit.internal;

import java.util.List;

import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the audit log. The table is append-only: this class only inserts and reads.
 */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String loggedAt, AuditObjectType objectType, String objectId, AuditAction action,
            String detail, String actor) {
        jdbc.sql("""
                        INSERT INTO audit_log (id, logged_at, object_type, object_id, action, detail, actor)
                        VALUES (:id, :loggedAt, :objectType, :objectId, :action, :detail, :actor)
                        """)
                .param("id", id)
                .param("loggedAt", loggedAt)
                .param("objectType", objectType.name())
                .param("objectId", objectId)
                .param("action", action.name())
                .param("detail", detail)
                .param("actor", actor)
                .update();
    }

    public List<AuditAction> findActions(AuditObjectType objectType, String objectId) {
        return jdbc.sql("""
                        SELECT action FROM audit_log
                        WHERE object_type = :objectType AND object_id = :objectId
                        ORDER BY logged_at, id
                        """)
                .param("objectType", objectType.name())
                .param("objectId", objectId)
                .query((rs, rowNum) -> AuditAction.valueOf(rs.getString("action")))
                .list();
    }
}
