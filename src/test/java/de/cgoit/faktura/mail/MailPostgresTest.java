package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
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
 * Verifies on PostgreSQL that the mail tables are portable, that the constraints hold and that sending and
 * logging work with the same SQL. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = { "spring.datasource.driver-class-name=org.postgresql.Driver",
        "faktura.mail.enabled=true", "spring.mail.host=smtp.example.test", "faktura.mail.from=rechnung@example.test",
        "spring.datasource.hikari.maximum-pool-size=4" })
@Import(TestMailConfiguration.class)
class MailPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg-mail");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private MailService mail;

    @Autowired
    private MailTemplateService templates;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CapturingMailSender sender;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        sender.reset();
    }

    @Test
    void seededTemplatesAndOneDefaultPerDocumentTypeExist() {
        assertThat(templates.list()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(templates.defaultFor(MailScope.NOTIFICATION_DIGEST)).isPresent();
        assertThat(templates.defaultFor(MailScope.INVOICE)).isPresent();
        assertThat(templates.defaultFor(MailScope.DUNNING)).isPresent();
    }

    @Test
    void secondDefaultInTheSameScopeIsRefusedByTheIndexAndBadValuesByTheChecks() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at)
                VALUES ('x1', 'INVOICE', 'Zweiter', 'S', 'B', 1, '2026-10-22T00:00:00Z')
                """).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO mail_template (id, scope, name, subject, body, is_default, created_at)
                VALUES ('x2', 'LETTER', 'Falsch', 'S', 'B', 0, '2026-10-22T00:00:00Z')
                """).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO mail_log (id, owner_type, owner_id, recipients, subject, body, status, sent_at)
                VALUES ('x3', 'DOCUMENT', 'd1', 'a@example.test', 'S', 'B', 'MAYBE', '2026-10-22T00:00:00Z')
                """).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void sendingLogsOnPostgresToo() {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, email, iban, bic, bank_name,
                                     payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Max Mustermann', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        'rechnung@example.test', 'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG keine Umsatzsteuer.')
                """).update();

        var customer = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, MailTestData.CUSTOMER_EMAIL, null, null, null));
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customer.id(), LocalDate.now(), null, null, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.none(), List.of(item)));
        var invoice = finalizer.finalizeInvoice(draft.id());
        var target = new MailTarget(MailOwnerType.DOCUMENT, invoice.id());
        var prepared = mail.prepare(target, null);

        mail.send(new SendMailCommand(target, prepared.recipients(), prepared.subject(), prepared.body(), true));

        assertThat(sender.sent()).hasSize(1);
        assertThat(mail.history(target)).singleElement().satisfies(entry -> {
            assertThat(entry.sent()).isTrue();
            assertThat(entry.recipients()).isEqualTo(MailTestData.CUSTOMER_EMAIL);
        });
    }
}
