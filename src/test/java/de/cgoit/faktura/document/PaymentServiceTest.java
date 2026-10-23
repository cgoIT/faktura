package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class PaymentServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private CustomerService customers;

    @Autowired
    private AuditService audit;

    @Autowired
    private JdbcClient jdbc;

    private String customerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, iban, bic, bank_name, payment_days,
                                     created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG keine Umsatzsteuer.')
                """).update();
        customerId = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, null, null, null, null)).id();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM payment WHERE reverses_payment_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        TestArchive.clear();
    }

    /** Finalized invoice of 1,000.00 EUR issued {@code daysAgo} days ago with a 14 day term. */
    private InvoiceInfo invoice(int daysAgo) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY.minusDays(daysAgo), null, null, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.none(), List.of(item)));

        return finalizer.finalizeInvoice(draft.id());
    }

    private static PaymentInput input(long cents) {
        return new PaymentInput(TODAY, cents, PaymentMethod.BANK_TRANSFER, "Ref", null);
    }

    @Test
    void partialAndFullPaymentChangeTheDerivedState() {
        var invoice = invoice(30);

        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.OPEN);
        assertThat(payments.balance(invoice.id()).overdue()).isTrue();

        payments.record(invoice.id(), input(40_000));
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.PARTIALLY_PAID);
        assertThat(payments.balance(invoice.id()).openCents()).isEqualTo(60_000);

        payments.record(invoice.id(), input(60_000));
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.PAID);
        assertThat(payments.balance(invoice.id()).overdue()).isFalse();
    }

    @Test
    void overpaymentIsAccepted() {
        var invoice = invoice(1);

        payments.record(invoice.id(), input(120_000));

        assertThat(payments.balance(invoice.id()).overpaidCents()).isEqualTo(20_000);
    }

    @Test
    void invoiceListCarriesTheBalance() {
        var invoice = invoice(30);
        payments.record(invoice.id(), input(25_000));

        var row = invoices.list("", null).getFirst();

        assertThat(row.balance().openCents()).isEqualTo(75_000);
        assertThat(row.balance().overdue()).isTrue();
        assertThat(row.balance().daysOverdue()).isEqualTo(16);
    }

    @Test
    void reversingEntryRestoresTheOpenAmountAndKeepsBothRows() {
        var invoice = invoice(1);
        var payment = payments.record(invoice.id(), input(100_000));

        var reversal = payments.reverse(invoice.id(), payment.id());

        assertThat(reversal.amountCents()).isEqualTo(-100_000);
        assertThat(reversal.reversesPaymentId()).isEqualTo(payment.id());
        assertThat(payments.list(invoice.id())).hasSize(2);
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.OPEN);
        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, invoice.id()))
                .contains(AuditAction.PAYMENT_RECORD, AuditAction.PAYMENT_REVERSE);
    }

    @Test
    void aPaymentCanBeReversedOnlyOnce() {
        var invoice = invoice(1);
        var payment = payments.record(invoice.id(), input(100_000));
        var reversal = payments.reverse(invoice.id(), payment.id());

        assertThatThrownBy(() -> payments.reverse(invoice.id(), payment.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("payment.error.alreadyReversed");
        assertThatThrownBy(() -> payments.reverse(invoice.id(), reversal.id()))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void rejectsDraftsCancelledInvoicesAndInvalidInput() {
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(new InvoiceItemInput("Beratung", 1_000, "Std", 10_000, 0, "E", 0, null))));
        var invoice = invoice(1);

        assertThatThrownBy(() -> payments.record(draft.id(), input(100)))
                .hasMessage("payment.error.notInvoice");
        assertThatThrownBy(() -> payments.record(invoice.id(), input(0)))
                .hasMessage("payment.error.amount");
        assertThatThrownBy(() -> payments.record(invoice.id(), input(-5)))
                .hasMessage("payment.error.amount");
        assertThatThrownBy(() -> payments.record(invoice.id(),
                new PaymentInput(TODAY.plusDays(1), 100, PaymentMethod.CASH, null, null)))
                .hasMessage("payment.error.future");

        finalizer.cancelInvoice(invoice.id());

        assertThatThrownBy(() -> payments.record(invoice.id(), input(100)))
                .hasMessage("payment.error.cancelled");
        assertThat(payments.balance(invoice.id()).state()).isEqualTo(PaymentState.CANCELLED);
    }

    @Test
    void aPaymentOnACancelledInvoiceCanStillBeReversed() {
        var invoice = invoice(1);
        var payment = payments.record(invoice.id(), input(100_000));

        finalizer.cancelInvoice(invoice.id());

        assertThat(payments.reverse(invoice.id(), payment.id()).amountCents()).isEqualTo(-100_000);
    }

    @Test
    void recordingAPaymentDoesNotTouchTheInvoice() {
        var invoice = invoice(1);
        var before = jdbc.sql("SELECT status, updated_at, gross_total_cents FROM document WHERE id = :id")
                .param("id", invoice.id()).query().singleRow();

        payments.record(invoice.id(), input(100_000));

        var after = jdbc.sql("SELECT status, updated_at, gross_total_cents FROM document WHERE id = :id")
                .param("id", invoice.id()).query().singleRow();
        assertThat(after).isEqualTo(before);
    }
}
