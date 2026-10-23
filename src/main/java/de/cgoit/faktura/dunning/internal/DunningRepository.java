package de.cgoit.faktura.dunning.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.dunning.DunningInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of created dunning letters. The table is append-only: this class only inserts and reads.
 */
@Repository
public class DunningRepository {

    private static final String SELECT = """
            SELECT id, document_id, level, level_name, number, dunning_date, open_cents, fee_cents, interest_cents,
                   new_due_date
            FROM dunning
            """;

    private final JdbcClient jdbc;

    public DunningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DunningInfo dunning, String createdAt) {
        jdbc.sql("""
                        INSERT INTO dunning (id, document_id, level, level_name, number, dunning_date, open_cents,
                                             fee_cents, interest_cents, new_due_date, created_at)
                        VALUES (:id, :documentId, :level, :levelName, :number, :date, :open, :fee, :interest,
                                :newDueDate, :createdAt)
                        """)
                .param("id", dunning.id())
                .param("documentId", dunning.invoiceId())
                .param("level", dunning.level())
                .param("levelName", dunning.levelName())
                .param("number", dunning.number())
                .param("date", dunning.date().toString())
                .param("open", dunning.openCents())
                .param("fee", dunning.feeCents())
                .param("interest", dunning.interestCents())
                .param("newDueDate", dunning.newDueDate().toString())
                .param("createdAt", createdAt)
                .update();
    }

    public Optional<DunningInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(DunningRepository::map)
                .optional();
    }

    public List<DunningInfo> findByInvoice(String invoiceId) {
        return jdbc.sql(SELECT + " WHERE document_id = :id ORDER BY level")
                .param("id", invoiceId)
                .query(DunningRepository::map)
                .list();
    }

    /**
     * Tells whether any letter was created with the given level number.
     */
    public boolean existsForLevel(int level) {
        return jdbc.sql("SELECT COUNT(*) FROM dunning WHERE level = :level")
                .param("level", level)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Lists all letters, newest first.
     */
    public List<DunningInfo> findAll() {
        return jdbc.sql(SELECT + " ORDER BY dunning_date DESC, number DESC")
                .query(DunningRepository::map)
                .list();
    }

    private static DunningInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new DunningInfo(rs.getString("id"), rs.getString("document_id"), rs.getInt("level"),
                rs.getString("level_name"), rs.getString("number"), LocalDate.parse(rs.getString("dunning_date")),
                rs.getLong("open_cents"), rs.getLong("fee_cents"), rs.getLong("interest_cents"),
                LocalDate.parse(rs.getString("new_due_date")));
    }
}
