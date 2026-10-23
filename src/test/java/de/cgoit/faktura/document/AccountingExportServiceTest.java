package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class AccountingExportServiceTest {

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

    private String customerId;
    private String customerNumber;

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

        var customer = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, null, null, null, null));

        customerId = customer.id();
        customerNumber = customer.number();
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

    private InvoiceInfo finalized(LocalDate issueDate, ShippingCharge shipping, String reference) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var input = new InvoiceInput(customerId, issueDate, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(item), null, reference, shipping);

        return finalizer.finalizeInvoice(invoices.createDraft(input).id());
    }

    private static PaymentInput payment(LocalDate date, long cents) {
        return new PaymentInput(date, cents, PaymentMethod.BANK_TRANSFER, "Ref", null);
    }

    @Test
    void listsFinalizedDocumentsOfTheYearWithStoredValues() {
        var shipping = new ShippingCharge("Versand", 500, "E", 0);
        var december = finalized(LocalDate.of(2025, 12, 31), shipping, "PO-1");
        var january = finalized(LocalDate.of(2026, 1, 1), null, null);

        invoices.createDraft(new InvoiceInput(customerId, LocalDate.of(2026, 2, 1), null, null, 14, "Entwurf", null, null,
                TotalDiscount.none(), List.of(new InvoiceItemInput("X", 10_000, "Std", 100, 0, "E", 0, null))));

        var rows2025 = export.invoices(2025);
        var rows2026 = export.invoices(2026);

        assertThat(rows2025).hasSize(1);
        assertThat(rows2025.getFirst().number()).isEqualTo(december.number());
        assertThat(rows2025.getFirst().customerNumber()).isEqualTo(customerNumber);
        assertThat(rows2025.getFirst().customerName()).isEqualTo("Muster GmbH");
        assertThat(rows2025.getFirst().customerReference()).isEqualTo("PO-1");
        assertThat(rows2025.getFirst().netCents()).isEqualTo(december.netTotalCents()).isEqualTo(100_500);
        assertThat(rows2025.getFirst().shippingCents()).isEqualTo(500);
        assertThat(rows2025.getFirst().grossCents()).isEqualTo(december.grossTotalCents());
        assertThat(rows2025.getFirst().state()).isEqualTo(PaymentState.OPEN);
        assertThat(rows2026).extracting(InvoiceExportRow::number).containsExactly(january.number());
        assertThat(rows2026.getFirst().shippingCents()).isZero();
        assertThat(export.invoices(2024)).isEmpty();
    }

    @Test
    void paymentsFollowThePaymentDateNotTheInvoiceYear() {
        var invoice = finalized(LocalDate.of(2025, 12, 31), null, null);

        payments.record(invoice.id(), payment(LocalDate.of(2026, 1, 15), 100_000));

        var row = export.invoices(2025).getFirst();

        assertThat(row.state()).isEqualTo(PaymentState.PAID);
        assertThat(row.paidOn()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(export.payments(2025)).isEmpty();
        assertThat(export.payments(2026)).hasSize(1);
        assertThat(export.payments(2026).getFirst().invoiceNumber()).isEqualTo(invoice.number());
        assertThat(export.payments(2026).getFirst().amountCents()).isEqualTo(100_000);
        assertThat(export.payments(2026).getFirst().reversal()).isFalse();
    }

    @Test
    void partialPaymentAndReversalAreListed() {
        var invoice = finalized(LocalDate.of(2026, 3, 1), null, null);
        var first = payments.record(invoice.id(), payment(LocalDate.of(2026, 3, 10), 40_000));

        assertThat(export.invoices(2026).getFirst().state()).isEqualTo(PaymentState.PARTIALLY_PAID);
        assertThat(export.invoices(2026).getFirst().paidOn()).isNull();

        payments.reverse(invoice.id(), first.id());

        var rows = export.payments(LocalDate.now().getYear());

        assertThat(rows).extracting(PaymentExportRow::amountCents).containsExactlyInAnyOrder(40_000L, -40_000L);
        assertThat(rows).filteredOn(PaymentExportRow::reversal).hasSize(1);
        assertThat(export.invoices(2026).getFirst().state()).isEqualTo(PaymentState.OPEN);
    }

    @Test
    void cancellationIsListedNegativeAndMarksTheOriginalCancelled() {
        var invoice = finalized(LocalDate.of(2026, 3, 1), new ShippingCharge("Versand", 500, "E", 0), null);
        var cancellation = finalizer.cancelInvoice(invoice.id());
        var year = cancellation.issueDate().getYear();

        var rows = export.invoices(year);
        var original = rows.stream()
                .filter(row -> row.type() == DocumentType.INVOICE)
                .findFirst()
                .orElseThrow();
        var storno = rows.stream()
                .filter(row -> row.type() == DocumentType.CANCELLATION)
                .findFirst()
                .orElseThrow();

        assertThat(original.state()).isEqualTo(PaymentState.CANCELLED);
        assertThat(original.grossCents()).isEqualTo(100_500);
        assertThat(storno.number()).isEqualTo(cancellation.number());
        assertThat(storno.grossCents()).isEqualTo(-100_500);
        assertThat(storno.netCents()).isEqualTo(-100_500);
        assertThat(storno.shippingCents()).isEqualTo(-500);
        assertThat(storno.state()).isNull();
        assertThat(storno.paidOn()).isNull();
    }

    @Test
    void yearsListsDataYearsNewestFirstAndAlwaysTheCurrentYear() {
        finalized(LocalDate.of(2024, 5, 1), null, null);

        assertThat(export.years()).containsExactly(LocalDate.now().getYear(), 2024);
    }
}
