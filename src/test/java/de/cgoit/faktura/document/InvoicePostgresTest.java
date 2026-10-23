package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.security.UserInput;
import de.cgoit.faktura.security.UserService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that the document schema and the draft lifecycle are portable. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class InvoicePostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private CustomerService customers;

    @Autowired
    private UserService users;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetData() {
        for (var table : new String[] {"app_user", "audit_log", "file", "document_tax_total", "document_item", "document",
                "customer_contact", "customer", "tax_profile", "company", "number_sequence"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    private void company(String regime, int rateBp, String category) {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, iban, payment_days, created_at)
                VALUES ('c-pg', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'DE', 'DE02120300000000202051', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category,
                                         exemption_text, allowed_categories)
                VALUES ('p-pg', 'c-pg', '2020-01-01', :regime, :rate, :category, 'Gemäß § 19 UStG', :allowed)
                """)
                .param("regime", regime)
                .param("rate", rateBp)
                .param("category", category)
                .param("allowed", "SMALL_BUSINESS".equals(regime) ? category : null)
                .update();
    }

    @Test
    void draftLifecycleAndLockWork() {
        company("REGULAR", 1900, "S");
        var customer = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null));
        var item = new InvoiceItemInput("Beratung", 1500, "Std", 10_000, 1000, "S", 1900, null);
        var input = new InvoiceInput(customer.id(), LocalDate.of(2026, 10, 20), null, null, 14, "Betreff", null, null,
                TotalDiscount.percent(500), List.of(item));

        var draft = invoices.createDraft(input);

        assertThat(draft.number()).isNull();
        assertThat(draft.netTotalCents()).isEqualTo(12_825);
        assertThat(draft.taxTotalCents()).isEqualTo(2_437);

        var changed = invoices.updateDraft(draft.id(), input, draft.version());
        assertThat(changed.items()).hasSize(1);
        assertThat(changed.version()).isEqualTo(draft.version() + 1);
        assertThatThrownBy(() -> invoices.updateDraft(draft.id(), input, draft.version()))
                .hasMessage("invoice.error.conflict");

        jdbc.sql("UPDATE document SET status = 'FINAL', number = 'LOCK-TEST-1' WHERE id = :id")
                .param("id", draft.id())
                .update();

        assertThatThrownBy(() -> invoices.deleteDraft(draft.id())).isInstanceOf(RuleViolationException.class);
        assertThat(invoices.find(draft.id()).orElseThrow().items()).hasSize(1);
    }

    @Test
    void finalizingAndCancellingWorkOnPostgres() {
        company("SMALL_BUSINESS", 0, "E");
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, null,
                null, null));
        var item = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customer.id(), LocalDate.of(2026, 10, 20), null, null, 14,
                null, null, null, TotalDiscount.none(), List.of(item), null, "PO-1",
                new ShippingCharge("Versand", 495, null, 0)));

        var invoice = finalizer.finalizeInvoice(draft.id());
        var cancellation = finalizer.cancelInvoice(invoice.id());

        assertThat(invoice.number()).isEqualTo("RE202600001");
        assertThat(invoice.grossTotalCents()).isEqualTo(10_495);
        assertThat(invoice.customerReference()).isEqualTo("PO-1");
        assertThat(cancellation.number()).isEqualTo("ST202600001");
        assertThat(cancellation.grossTotalCents()).isEqualTo(-10_495);
        assertThat(cancellation.shipping()).isEqualTo(new ShippingCharge("Versand", -495, "E", 0));
        assertThat(finalizer.findPdf(invoice.id())).isPresent();
        assertThat(jdbc.sql("SELECT DISTINCT actor FROM audit_log").query(String.class).list())
                .containsExactly("system");
        assertThatThrownBy(() -> finalizer.cancelInvoice(invoice.id())).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void usersWorkOnPostgres() {
        var created = users.create(new UserInput("Anna", "Anna Beispiel", "a long enough password", true));

        assertThat(created.username()).isEqualTo("anna");
        assertThat(users.list()).hasSize(1);
        assertThatThrownBy(() -> users.setActive(created.id(), false)).hasMessage("user.error.lastAdmin");
    }
}
