package de.cgoit.faktura.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.AccountingExportService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.document.PaymentState;
import de.cgoit.faktura.document.TotalDiscount;
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
 * Verifies on PostgreSQL that the export queries are portable. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class ExportPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg-export");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private AccountingExportService export;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetData() {
        for (var table : new String[] {"payment", "app_user", "audit_log", "file", "document_tax_total", "document_item",
                "document", "customer_contact", "customer", "tax_profile", "company", "number_sequence"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void exportReadsInvoicesTaxTotalsAndPayments() {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, iban, payment_days, created_at)
                VALUES ('c-pg', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'DE', 'DE02120300000000202051', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category,
                                         exemption_text)
                VALUES ('p-pg', 'c-pg', '2020-01-01', 'REGULAR', 1900, 'S', NULL)
                """).update();

        var customer = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null));
        var items = List.of(new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "S", 1900, null),
                new InvoiceItemInput("Buch", 10_000, "Std", 5_000, 0, "S", 700, null));
        var draft = invoices.createDraft(new InvoiceInput(customer.id(), LocalDate.of(2026, 5, 4), null, null, 14,
                "Betreff", null, null, TotalDiscount.none(), items));
        var invoice = finalizer.finalizeInvoice(draft.id());

        payments.record(invoice.id(), new PaymentInput(LocalDate.of(2026, 5, 20), invoice.grossTotalCents(),
                PaymentMethod.BANK_TRANSFER, "Ref", null));

        var rows = export.invoices(2026);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().taxTotals()).hasSize(2);
        assertThat(rows.getFirst().state()).isEqualTo(PaymentState.PAID);
        assertThat(rows.getFirst().paidOn()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(export.payments(2026)).hasSize(1);
        assertThat(export.years()).contains(2026);
    }
}
