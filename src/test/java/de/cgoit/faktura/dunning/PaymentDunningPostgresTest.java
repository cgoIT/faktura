package de.cgoit.faktura.dunning;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that the payment and dunning tables are portable and that the check constraints
 * hold. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class PaymentDunningPostgresTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg-dunning");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private DunningService dunnings;

    @Autowired
    private DunningLevelService levels;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetData() {
        for (var table : new String[] {"dunning", "payment", "audit_log", "file", "document_tax_total", "document_item",
                "document", "customer_contact", "customer", "tax_profile", "company", "number_sequence"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }

        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, iban, payment_days, created_at)
                VALUES ('c-pg', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'DE', 'DE02120300000000202051', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category,
                                         exemption_text, allowed_categories)
                VALUES ('p-pg', 'c-pg', '2020-01-01', 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG', 'E')
                """).update();
    }

    @Test
    void defaultLevelsAreSeeded() {
        assertThat(levels.list()).extracting(DunningLevelInfo::level).containsExactly(1, 2, 3);
    }

    @Test
    void paymentsReversalsAndDunningLettersWorkOnPostgres() {
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, null,
                null, null));
        var item = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customer.id(), TODAY.minusDays(30), null, null, 14, null,
                null, null, TotalDiscount.none(), List.of(item)));
        var invoice = finalizer.finalizeInvoice(draft.id());

        var payment = payments.record(invoice.id(), new PaymentInput(TODAY, 4_000, PaymentMethod.CASH, "Bar", null));
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.PARTIALLY_PAID);

        payments.reverse(invoice.id(), payment.id());
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.OPEN);
        assertThat(payments.list(invoice.id())).hasSize(2);

        var letter = dunnings.create(invoice.id(), levels.list().getFirst().id());

        assertThat(letter.number()).isEqualTo("MA" + TODAY.getYear() + "00001");
        assertThat(dunnings.findPdf(letter.id())).isPresent();
        assertThat(dunnings.suggestions()).isEmpty();
    }

    @Test
    void checkConstraintsRejectInvalidPayments() {
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, null,
                null, null));
        var item = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customer.id(), TODAY, null, null, 14, null, null, null,
                TotalDiscount.none(), List.of(item)));
        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO payment (id, document_id, payment_date, amount_cents, method, created_at)
                VALUES ('x1', :id, '2026-10-20', -5, 'CASH', '2026-10-20T00:00:00Z')
                """).param("id", invoice.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO payment (id, document_id, payment_date, amount_cents, method, created_at)
                VALUES ('x2', :id, '2026-10-20', 5, 'BITCOIN', '2026-10-20T00:00:00Z')
                """).param("id", invoice.id()).update()).isInstanceOf(DataAccessException.class);
    }
}
