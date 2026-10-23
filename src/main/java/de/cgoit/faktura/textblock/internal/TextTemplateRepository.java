package de.cgoit.faktura.textblock.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of text templates.
 */
@Repository
public class TextTemplateRepository {

    private static final String SELECT = "SELECT id, scope, kind, name, body, is_default FROM text_template";

    private final JdbcClient jdbc;
    private final Clock clock;

    public TextTemplateRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<TextTemplateInfo> findAll() {
        return jdbc.sql(SELECT + " ORDER BY scope, kind, name, id")
                .query(TextTemplateRepository::map)
                .list();
    }

    public List<TextTemplateInfo> find(TextScope scope, TextKind kind) {
        return jdbc.sql(SELECT + " WHERE scope = :scope AND kind = :kind ORDER BY name, id")
                .param("scope", scope.name())
                .param("kind", kind.name())
                .query(TextTemplateRepository::map)
                .list();
    }

    public Optional<TextTemplateInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(TextTemplateRepository::map)
                .optional();
    }

    public Optional<TextTemplateInfo> findDefault(TextScope scope, TextKind kind) {
        return jdbc.sql(SELECT + " WHERE scope = :scope AND kind = :kind AND is_default = 1")
                .param("scope", scope.name())
                .param("kind", kind.name())
                .query(TextTemplateRepository::map)
                .optional();
    }

    public void insert(String id, TextScope scope, TextKind kind, String name, String body) {
        jdbc.sql("""
                        INSERT INTO text_template (id, scope, kind, name, body, is_default, created_at)
                        VALUES (:id, :scope, :kind, :name, :body, 0, :now)
                        """)
                .param("id", id)
                .param("scope", scope.name())
                .param("kind", kind.name())
                .param("name", name)
                .param("body", body)
                .param("now", Instant.now(clock).toString())
                .update();
    }

    public void update(String id, String name, String body) {
        jdbc.sql("UPDATE text_template SET name = :name, body = :body WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .param("body", body)
                .update();
    }

    public void clearDefault(TextScope scope, TextKind kind) {
        jdbc.sql("UPDATE text_template SET is_default = 0 WHERE scope = :scope AND kind = :kind")
                .param("scope", scope.name())
                .param("kind", kind.name())
                .update();
    }

    public void markDefault(String id) {
        jdbc.sql("UPDATE text_template SET is_default = 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM text_template WHERE id = :id")
                .param("id", id)
                .update();
    }

    private static TextTemplateInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new TextTemplateInfo(rs.getString("id"), TextScope.valueOf(rs.getString("scope")),
                TextKind.valueOf(rs.getString("kind")), rs.getString("name"), rs.getString("body"),
                rs.getInt("is_default") == 1);
    }
}
