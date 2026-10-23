package de.cgoit.faktura.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import de.cgoit.faktura.mail.CapturingMailSender;
import de.cgoit.faktura.mail.TestMailConfiguration;
import de.cgoit.faktura.notification.internal.NotificationRepository;
import de.cgoit.faktura.notification.internal.NotificationRepository.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that the notification tables are portable: the unique key and the idempotent insert
 * of the log, the checks, the settings row and a mail to the operator. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = { "spring.datasource.driver-class-name=org.postgresql.Driver",
        "faktura.mail.enabled=true", "spring.mail.host=smtp.example.test", "faktura.mail.from=rechnung@example.test",
        "faktura.notification.enabled=false", "spring.datasource.hikari.maximum-pool-size=4" })
@Import(TestMailConfiguration.class)
class NotificationPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg-notification");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private NotificationService notifications;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private CapturingMailSender sender;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        sender.reset();
        jdbc.sql("DELETE FROM notification_log").update();
        jdbc.sql("DELETE FROM company").update();
    }

    @Test
    void theSettingsRowExistsWithEverythingSwitchedOff() {
        var settings = notifications.settings();

        assertThat(settings.anyEnabled()).isFalse();
        assertThat(settings.failureCount()).isZero();
        assertThat(settings.lastRunDate()).isNull();
    }

    @Test
    void loggingTheSameItemTwiceKeepsOneRowAndNeverFails() {
        var item = new Item(NotificationCategory.OVERDUE, "d1", "0");

        repository.log(List.of(item));
        repository.log(List.of(item, new Item(NotificationCategory.DUNNING_DUE, "d1", "1")));

        assertThat(repository.reported(NotificationCategory.OVERDUE)).containsExactly("d1|0");
        assertThat(repository.reported(NotificationCategory.DUNNING_DUE)).containsExactly("d1|1");
    }

    @Test
    void theDatabaseRefusesADuplicateAndAnUnknownCategory() {
        jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l1', 'DRAFT', 'd1', '0', '2026-10-23T00:00:00Z')").update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l2', 'DRAFT', 'd1', '0', '2026-10-24T00:00:00Z')").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l3', 'OTHER', 'd2', '0', '2026-10-24T00:00:00Z')").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void savingTheSettingsAndSendingATestMailWork() {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, email, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Max Mustermann', 'DE', 'betreiber@example.test', 14,
                        '2026-01-01T00:00:00Z')
                """).update();

        var saved = notifications.update(new NotificationInput(true, false, true, false, null, "07:45"));

        assertThat(saved.draftEnabled()).isTrue();
        assertThat(saved.sendTime()).isEqualTo("07:45");

        notifications.sendTest();

        assertThat(sender.sent()).hasSize(1);

        notifications.update(new NotificationInput(false, false, false, false, null, null));
    }
}
