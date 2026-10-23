package de.cgoit.faktura.mail.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import de.cgoit.faktura.mail.MailLogEntry;
import de.cgoit.faktura.mail.MailOwnerType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the mail log. Rows are only inserted: a send attempt is proof and is never changed or deleted.
 */
@Repository
public class MailLogRepository {

    private static final String SELECT = """
            SELECT id, owner_type, owner_id, recipients, subject, body, attachment_name, status, error_key, sent_at
            FROM mail_log
            """;

    private final JdbcClient jdbc;

    public MailLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO mail_log (id, owner_type, owner_id, recipients, subject, body, attachment_file_id,
                            attachment_name, status, error_key, message_id, sent_at)
                        VALUES (:id, :ownerType, :ownerId, :recipients, :subject, :body, :fileId, :fileName, :status,
                            :errorKey, :messageId, :sentAt)
                        """)
                .param("id", row.id())
                .param("ownerType", row.ownerType().name())
                .param("ownerId", row.ownerId())
                .param("recipients", row.recipients())
                .param("subject", row.subject())
                .param("body", row.body())
                .param("fileId", row.attachmentFileId())
                .param("fileName", row.attachmentName())
                .param("status", row.sent() ? "SENT" : "FAILED")
                .param("errorKey", row.errorKey())
                .param("messageId", row.messageId())
                .param("sentAt", row.sentAt().toString())
                .update();
    }

    /**
     * Lists the attempts for an object, newest first.
     */
    public List<MailLogEntry> findByOwner(MailOwnerType type, String ownerId, ZoneId zone) {
        return jdbc.sql(SELECT + " WHERE owner_type = :type AND owner_id = :id ORDER BY sent_at DESC, id")
                .param("type", type.name())
                .param("id", ownerId)
                .query((rs, rowNum) -> map(rs, zone))
                .list();
    }

    private static MailLogEntry map(ResultSet rs, ZoneId zone) throws SQLException {
        return new MailLogEntry(rs.getString("id"), MailOwnerType.valueOf(rs.getString("owner_type")),
                rs.getString("owner_id"), rs.getString("recipients"), rs.getString("subject"), rs.getString("body"),
                rs.getString("attachment_name"), "SENT".equals(rs.getString("status")), rs.getString("error_key"),
                LocalDateTime.ofInstant(Instant.parse(rs.getString("sent_at")), zone));
    }

    /**
     * The values of one log row.
     */
    public record Row(String id, MailOwnerType ownerType, String ownerId, String recipients, String subject,
            String body, String attachmentFileId, String attachmentName, boolean sent, String errorKey,
            String messageId, Instant sentAt) {

        /**
         * Hides personal data from logs.
         */
        @Override
        public String toString() {
            return "Row[id=" + id + "]";
        }
    }
}
