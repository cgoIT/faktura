package de.cgoit.faktura.dunning.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.dunning.DunningLevelInfo;
import de.cgoit.faktura.dunning.DunningLevelInput;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the dunning level configuration. Levels are configuration, not records: they may change, but a
 * created dunning letter keeps its own copy of level number and name.
 */
@Repository
public class DunningLevelRepository {

    private static final String SELECT = """
            SELECT id, level, name, days_after_due, fee_cents, interest_bp, new_due_days, header_text, footer_text,
                   active
            FROM dunning_level
            """;

    private final JdbcClient jdbc;

    public DunningLevelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DunningLevelInfo> findAll() {
        return jdbc.sql(SELECT + " ORDER BY level")
                .query(DunningLevelRepository::map)
                .list();
    }

    public Optional<DunningLevelInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(DunningLevelRepository::map)
                .optional();
    }

    public Optional<DunningLevelInfo> findByLevel(int level) {
        return jdbc.sql(SELECT + " WHERE level = :level")
                .param("level", level)
                .query(DunningLevelRepository::map)
                .optional();
    }

    public void insert(String id, DunningLevelInput input) {
        jdbc.sql("""
                        INSERT INTO dunning_level (id, level, name, days_after_due, fee_cents, interest_bp,
                                                   new_due_days, header_text, footer_text, active)
                        VALUES (:id, :level, :name, :days, :fee, :interest, :newDue, :header, :footer, :active)
                        """)
                .param("id", id)
                .param("level", input.level())
                .param("name", input.name())
                .param("days", input.daysAfterDue())
                .param("fee", input.feeCents())
                .param("interest", input.interestBp())
                .param("newDue", input.newDueDays())
                .param("header", input.headerText())
                .param("footer", input.footerText())
                .param("active", input.active() ? 1 : 0)
                .update();
    }

    public void update(String id, DunningLevelInput input) {
        jdbc.sql("""
                        UPDATE dunning_level
                        SET level = :level, name = :name, days_after_due = :days, fee_cents = :fee,
                            interest_bp = :interest, new_due_days = :newDue, header_text = :header,
                            footer_text = :footer, active = :active
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("level", input.level())
                .param("name", input.name())
                .param("days", input.daysAfterDue())
                .param("fee", input.feeCents())
                .param("interest", input.interestBp())
                .param("newDue", input.newDueDays())
                .param("header", input.headerText())
                .param("footer", input.footerText())
                .param("active", input.active() ? 1 : 0)
                .update();
    }

    private static DunningLevelInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new DunningLevelInfo(rs.getString("id"), rs.getInt("level"), rs.getString("name"),
                rs.getInt("days_after_due"), rs.getLong("fee_cents"), rs.getInt("interest_bp"),
                rs.getInt("new_due_days"), rs.getString("header_text"), rs.getString("footer_text"),
                rs.getInt("active") == 1);
    }
}
