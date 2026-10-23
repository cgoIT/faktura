package de.cgoit.faktura.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class AuditServiceTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM audit_log").update();
    }

    @Test
    void entryIsWrittenInTheCallersTransaction() {
        transaction.executeWithoutResult(status ->
                audit.record(AuditObjectType.DOCUMENT, "d1", AuditAction.FINALIZE, Map.of("number", "RE1")));

        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, "d1")).containsExactly(AuditAction.FINALIZE);
    }

    @Test
    void rolledBackEventLeavesNoEntry() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            audit.record(AuditObjectType.DOCUMENT, "d2", AuditAction.FINALIZE, Map.of());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, "d2")).isEmpty();
    }

    @Test
    void entryNamesTheSystemWhenNobodyIsSignedIn() {
        transaction.executeWithoutResult(status ->
                audit.record(AuditObjectType.DOCUMENT, "d4", AuditAction.FINALIZE, Map.of()));

        var actor = jdbc.sql("SELECT actor FROM audit_log WHERE object_id = 'd4'")
                .query(String.class)
                .single();

        assertThat(actor).isEqualTo("system");
    }

    @Test
    void recordingWithoutTransactionFails() {
        assertThatThrownBy(() -> audit.record(AuditObjectType.DOCUMENT, "d3", AuditAction.CANCEL, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
