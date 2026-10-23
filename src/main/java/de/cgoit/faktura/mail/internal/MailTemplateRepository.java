package de.cgoit.faktura.mail.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailTemplateInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of mail templates.
 */
@Repository
public class MailTemplateRepository {

    private static final String SELECT = "SELECT id, scope, name, subject, body, is_default FROM mail_template";

    private final JdbcClient jdbc;
    private final Clock clock;

    public MailTemplateRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<MailTemplateInfo> findAll() {
        return jdbc.sql(SELECT + " ORDER BY scope, name, id")
                .query(MailTemplateRepository::map)
                .list();
    }

    public List<MailTemplateInfo> find(MailScope scope) {
        return jdbc.sql(SELECT + " WHERE scope = :scope ORDER BY name, id")
                .param("scope", scope.name())
                .query(MailTemplateRepository::map)
                .list();
    }

    public Optional<MailTemplateInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(MailTemplateRepository::map)
                .optional();
    }

    public Optional<MailTemplateInfo> findDefault(MailScope scope) {
        return jdbc.sql(SELECT + " WHERE scope = :scope AND is_default = 1")
                .param("scope", scope.name())
                .query(MailTemplateRepository::map)
                .optional();
    }

    public void insert(String id, MailScope scope, String name, String subject, String body) {
        jdbc.sql("""
                        INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at)
                        VALUES (:id, :scope, :name, :subject, :body, 0, :now)
                        """)
                .param("id", id)
                .param("scope", scope.name())
                .param("name", name)
                .param("subject", subject)
                .param("body", body)
                .param("now", Instant.now(clock).toString())
                .update();
    }

    public void update(String id, String name, String subject, String body) {
        jdbc.sql("UPDATE mail_template SET name = :name, subject = :subject, body = :body WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .param("subject", subject)
                .param("body", body)
                .update();
    }

    public void clearDefault(MailScope scope) {
        jdbc.sql("UPDATE mail_template SET is_default = 0 WHERE scope = :scope")
                .param("scope", scope.name())
                .update();
    }

    public void markDefault(String id) {
        jdbc.sql("UPDATE mail_template SET is_default = 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM mail_template WHERE id = :id")
                .param("id", id)
                .update();
    }

    private static MailTemplateInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new MailTemplateInfo(rs.getString("id"), MailScope.valueOf(rs.getString("scope")), rs.getString("name"),
                rs.getString("subject"), rs.getString("body"), rs.getInt("is_default") == 1);
    }
}
