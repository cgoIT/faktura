package de.cgoit.faktura.notification.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import de.cgoit.faktura.notification.NotificationCategory;
import de.cgoit.faktura.notification.NotificationSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the notification settings (one row) and of the insert-only log of reported items.
 */
@Repository
public class NotificationRepository {

    private static final String SETTING_ID = "default";

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * An item that was or is to be reported.
     *
     * @param objectId document or plan the item is about
     * @param stage    dunning level, error key, or {@code 0}
     */
    public record Item(NotificationCategory category, String objectId, String stage) {
    }

    public NotificationSettings settings() {
        return jdbc.sql("""
                        SELECT draft_enabled, plan_error_enabled, overdue_enabled, dunning_enabled, offer_enabled,
                               recipient, send_time, last_run_date, failure_count
                        FROM notification_setting WHERE id = :id
                        """)
                .param("id", SETTING_ID)
                .query((rs, rowNum) -> new NotificationSettings(rs.getInt("draft_enabled") == 1,
                        rs.getInt("plan_error_enabled") == 1, rs.getInt("overdue_enabled") == 1,
                        rs.getInt("dunning_enabled") == 1, rs.getInt("offer_enabled") == 1,
                        rs.getString("recipient"), rs.getString("send_time"),
                        rs.getString("last_run_date") == null ? null : LocalDate.parse(rs.getString("last_run_date")),
                        rs.getInt("failure_count")))
                .single();
    }

    public void updateSettings(boolean draft, boolean planError, boolean overdue, boolean dunning, boolean offer,
            String recipient, String sendTime) {
        jdbc.sql("""
                        UPDATE notification_setting
                        SET draft_enabled = :draft, plan_error_enabled = :planError, overdue_enabled = :overdue,
                            dunning_enabled = :dunning, offer_enabled = :offer, recipient = :recipient,
                            send_time = :sendTime, failure_count = 0
                        WHERE id = :id
                        """)
                .param("id", SETTING_ID)
                .param("draft", draft ? 1 : 0)
                .param("planError", planError ? 1 : 0)
                .param("overdue", overdue ? 1 : 0)
                .param("dunning", dunning ? 1 : 0)
                .param("offer", offer ? 1 : 0)
                .param("recipient", recipient)
                .param("sendTime", sendTime)
                .update();
    }

    /**
     * Remembers a run that finished without a mail error and clears the failure counter.
     */
    public void markRun(LocalDate day) {
        jdbc.sql("UPDATE notification_setting SET last_run_date = :day, failure_count = 0 WHERE id = :id")
                .param("id", SETTING_ID)
                .param("day", day.toString())
                .update();
    }

    public void countFailure() {
        jdbc.sql("UPDATE notification_setting SET failure_count = failure_count + 1 WHERE id = :id")
                .param("id", SETTING_ID)
                .update();
    }

    public void resetFailures() {
        jdbc.sql("UPDATE notification_setting SET failure_count = 0 WHERE id = :id")
                .param("id", SETTING_ID)
                .update();
    }

    /**
     * Returns the already reported items of a category as {@code objectId|stage}.
     */
    public Set<String> reported(NotificationCategory category) {
        return new HashSet<>(jdbc.sql("""
                        SELECT object_id || '|' || stage FROM notification_log WHERE category = :category
                        """)
                .param("category", category.name())
                .query(String.class)
                .list());
    }

    /**
     * Records items as reported. An item that is already there is left alone.
     */
    public void log(List<Item> items) {
        var now = Instant.now(clock)
                .toString();

        for (var item : items) {
            jdbc.sql("""
                            INSERT INTO notification_log (id, category, object_id, stage, sent_at)
                            VALUES (:id, :category, :objectId, :stage, :now)
                            ON CONFLICT (category, object_id, stage) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("category", item.category().name())
                    .param("objectId", item.objectId())
                    .param("stage", item.stage())
                    .param("now", now)
                    .update();
        }
    }
}
