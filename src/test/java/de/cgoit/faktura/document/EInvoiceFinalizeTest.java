package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchivedFile;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.shared.RuleViolationException;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mustangproject.validator.ZUGFeRDValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Finalizes invoices of customers with and without e-invoice and lets the Mustang validator check the archived PDF.
 * All data is invented.
 */
@SqliteTest
@TestPropertySource(properties = "faktura.archive.export-path=target/test-export-finalize")
class EInvoiceFinalizeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private CustomerService customers;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private JdbcClient jdbc;

    private static final Path EXPORT = Path.of("target/test-export-finalize").toAbsolutePath();

    private static void clearExport() {
        try (var files = Files.walk(EXPORT)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            // directory does not exist yet
        }
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        try {
            Files.createDirectories(EXPORT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        company("123/456/78901");
        profile("p1", "2020-01-01", "2026-12-31", "SMALL_BUSINESS", 0, "E",
                "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.");
        profile("p2", "2027-01-01", null, "REGULAR", 1900, "S", null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file_export").update();
        jdbc.sql("DELETE FROM file").update();
        clearExport();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_category_reason").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        TestArchive.clear();
    }

    @Test
    void zugferdPdfIsCopiedToTheExportFolderWithoutTheSeparateXml() throws Exception {
        var invoice = finalizer.finalizeInvoice(draft(customer(EInvoiceMode.ZUGFERD), TODAY,
                item("Beratung", 2000, 9000, 0, "E", 0)).id());
        var pdf = file(invoice.id(), ArchiveFileType.PDF);
        var copy = EXPORT.resolve("RE202600001.pdf");

        for (var i = 0; i < 100 && Files.notExists(copy); i++) {
            Thread.sleep(100);
        }

        assertThat(copy).hasBinaryContent(read(pdf.path()));
        assertThat(sha256(Files.readAllBytes(copy))).isEqualTo(pdf.sha256());
        assertThat(EXPORT.resolve("RE202600001.xml")).doesNotExist();
    }

    @Test
    void smallBusinessInvoiceIsArchivedAsValidatedZugferdWithItsXml() throws Exception {
        var draft = draft(customer(EInvoiceMode.ZUGFERD), TODAY, item("Webentwicklung", 6000, 8500, 1000, "E", 0),
                item("Domain-Registrierung", 1000, 1200, 0, "E", 0), item("Anfahrt", 1000, 4000, 0, "E", 0));

        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThat(invoice.grossTotalCents()).isEqualTo(48_545);

        var pdf = file(invoice.id(), ArchiveFileType.PDF);
        var xml = file(invoice.id(), ArchiveFileType.XML);

        assertThat(xml.path()).isEqualTo("2026/RE202600001.xml");
        assertThat(xml.sha256()).isEqualTo(sha256(read(xml.path())));
        assertThat(pdf.sha256()).isEqualTo(sha256(read(pdf.path())));
        assertThat(new String(read(xml.path()), StandardCharsets.UTF_8))
                .contains("<ram:GrandTotalAmount>485.45</ram:GrandTotalAmount>", "RE202600001");
        assertValid(read(pdf.path()));
        assertThat(finalizer.findPdf(invoice.id())).isPresent();
    }

    @Test
    void regularInvoiceWithTwoRatesAndTotalDiscountIsValid() throws Exception {
        var draft = invoices.createDraft(new InvoiceInput(customer(EInvoiceMode.ZUGFERD), LocalDate.of(2027, 2, 1),
                null, null, 14, "Betreff", "Kopf", "Fuß", TotalDiscount.percent(1000),
                List.of(item("Beratung", 1000, 100_000, 0, "S", 1900), item("Unterlagen", 1000, 10_000, 0, "S", 700))));

        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThat(invoice.grossTotalCents()).isEqualTo(116_730);
        assertValid(read(file(invoice.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void cancellationOfAZugferdInvoiceIsACreditNote() throws Exception {
        var invoice = finalizer.finalizeInvoice(draft(customer(EInvoiceMode.ZUGFERD), TODAY,
                item("Beratung", 2000, 9000, 0, "E", 0)).id());

        var cancellation = finalizer.cancelInvoice(invoice.id());

        var xml = new String(read(file(cancellation.id(), ArchiveFileType.XML).path()), StandardCharsets.UTF_8);

        assertThat(xml).contains("<ram:TypeCode>381</ram:TypeCode>", invoice.number())
                .contains("<ram:GrandTotalAmount>171.00</ram:GrandTotalAmount>");
        assertValid(read(file(cancellation.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void invoiceWithoutEInvoiceStaysAPlainPdf() throws Exception {
        var invoice = finalizer.finalizeInvoice(draft(customer(EInvoiceMode.NONE), TODAY,
                item("Beratung", 2000, 9000, 0, "E", 0)).id());

        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()))
                .extracting(file -> file.type())
                .containsExactly(ArchiveFileType.PDF);

        try (var pdf = Loader.loadPDF(read(file(invoice.id(), ArchiveFileType.PDF).path()))) {
            assertThat(pdf.getDocumentCatalog().getNames()).isNull();
        }
    }

    @Test
    void sellerWithoutTaxIdsRollsBackAndTheNumberStaysFree() {
        jdbc.sql("UPDATE company SET tax_number = NULL, vat_id = NULL").update();

        var draft = draft(customer(EInvoiceMode.ZUGFERD), TODAY, item("Beratung", 2000, 9000, 0, "E", 0));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("einvoice.error.sellerTaxId");

        assertThat(invoices.find(draft.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id())).isEmpty();

        jdbc.sql("UPDATE company SET tax_number = '123/456/78901'").update();

        assertThat(finalizer.finalizeInvoice(draft.id()).number()).isEqualTo("RE202600001");
    }

    @Test
    void blankExemptionReasonRollsBackWithAClearMessage() {
        jdbc.sql("UPDATE tax_profile SET exemption_text = NULL").update();
        jdbc.sql("INSERT INTO tax_category_reason (category, reason) VALUES ('E', '')").update();

        var draft = draft(customer(EInvoiceMode.ZUGFERD), TODAY, item("Beratung", 2000, 9000, 0, "E", 0));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("einvoice.error.exemptionReason");

        assertThat(invoices.find(draft.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id())).isEmpty();
    }

    @Test
    void laterChangeOfTheCustomerModeDoesNotChangeTheFinalizedInvoice() {
        var customerId = customer(EInvoiceMode.ZUGFERD);
        var invoice = finalizer.finalizeInvoice(draft(customerId, TODAY, item("Beratung", 2000, 9000, 0, "E", 0)).id());

        customers.update(customerId, new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, null, null, null, null, EInvoiceMode.NONE));

        var snapshot = jdbc.sql("SELECT recipient_snapshot FROM document WHERE id = :id")
                .param("id", invoice.id())
                .query(String.class)
                .single();

        assertThat(snapshot).contains("ZUGFERD");
        assertThat(file(invoice.id(), ArchiveFileType.XML)).isNotNull();
    }

    @Test
    void cancellationOfARegularInvoiceWithTwoRatesAndDiscountIsValid() throws Exception {
        var customerId = customer(EInvoiceMode.ZUGFERD);
        var invoice = finalizer.finalizeInvoice(invoices.createDraft(new InvoiceInput(customerId,
                LocalDate.of(2027, 2, 1), null, null, 14, "Betreff", "Kopf", "Fuß", TotalDiscount.percent(1000),
                List.of(item("Beratung", 1000, 100_000, 0, "S", 1900), item("Unterlagen", 1000, 10_001, 0, "S", 700))))
                .id());

        var cancellation = finalizer.cancelInvoice(invoice.id());

        assertThat(cancellation.grossTotalCents()).isEqualTo(-invoice.grossTotalCents());
        assertValid(read(file(cancellation.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void fullTotalDiscountAndFullItemDiscountStayValid() throws Exception {
        var customerId = customer(EInvoiceMode.ZUGFERD);
        var invoice = finalizer.finalizeInvoice(invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null,
                14, "Betreff", "Kopf", "Fuß", TotalDiscount.percent(10_000),
                List.of(item("Beratung", 1000, 9000, 0, "E", 0), item("Geschenk", 1000, 5000, 10_000, "E", 0))))
                .id());

        assertThat(invoice.grossTotalCents()).isZero();
        assertValid(read(file(invoice.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void exemptCategoryUnderRegularTaxationUsesTheReasonOfTheCategory() throws Exception {
        jdbc.sql("INSERT INTO tax_category_reason (category, reason) VALUES ('E', 'Steuerfrei nach Eigentext')")
                .update();

        var invoice = finalizer.finalizeInvoice(invoices.createDraft(new InvoiceInput(customer(EInvoiceMode.ZUGFERD),
                LocalDate.of(2027, 2, 1), null, null, 14, "Betreff", "Kopf", "Fuß", TotalDiscount.none(),
                List.of(item("Beratung", 1000, 10_000, 0, "S", 1900), item("Steuerfrei", 1000, 5000, 0, "E", 0))))
                .id());
        var xml = new String(read(file(invoice.id(), ArchiveFileType.XML).path()), StandardCharsets.UTF_8);

        assertThat(xml).contains("Steuerfrei nach Eigentext");
        assertValid(read(file(invoice.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void reviewRunsTheEInvoiceTrialWithoutStoringAnything() {
        var customerId = customer(EInvoiceMode.ZUGFERD);
        var draft = draft(customerId, TODAY, item("Beratung", 2000, 9000, 0, "E", 0));
        var input = new InvoiceInput(customerId, TODAY, null, null, 30, "Betreff", "Hallo {kunde}",
                "Fällig {faelligkeit}", TotalDiscount.none(), List.of(item("Beratung", 2000, 9000, 0, "E", 0)));

        var review = finalizer.review(draft.id(), input);

        assertThat(review.ok()).isTrue();
        assertThat(review.einvoiceMode()).isEqualTo(EInvoiceMode.ZUGFERD);
        assertThat(review.calculation().grossCents()).isEqualTo(18_000);
        assertThat(review.dueDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(review.headerText()).contains("Muster GmbH");
        assertThat(review.footerText()).contains("19.11.2026");
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id())).isEmpty();
        assertThat(invoices.find(draft.id()).orElseThrow().number()).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM number_sequence WHERE scope = 'INVOICE'")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void reviewReportsMissingSellerTaxIdsBeforeTheUserConfirms() {
        jdbc.sql("UPDATE company SET tax_number = NULL, vat_id = NULL").update();

        var customerId = customer(EInvoiceMode.ZUGFERD);
        var draft = draft(customerId, TODAY, item("Beratung", 2000, 9000, 0, "E", 0));
        var input = new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff", null, null, TotalDiscount.none(),
                List.of(item("Beratung", 2000, 9000, 0, "E", 0)));

        assertThatThrownBy(() -> finalizer.review(draft.id(), input))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("einvoice.error.sellerTaxId");
    }

    @Test
    void reviewOfAFinalizedInvoiceIsRefused() {
        var customerId = customer(EInvoiceMode.NONE);
        var invoice = finalizer.finalizeInvoice(draft(customerId, TODAY, item("Beratung", 2000, 9000, 0, "E", 0)).id());
        var input = new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff", null, null, TotalDiscount.none(),
                List.of(item("Beratung", 2000, 9000, 0, "E", 0)));

        assertThatThrownBy(() -> finalizer.review(invoice.id(), input)).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void smallBusinessInvoiceWithShippingAndReferenceIsValidAndKeepsBothOnTheDocument() throws Exception {
        var customerId = customer(EInvoiceMode.ZUGFERD);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff",
                "Ref {kundenreferenz}", "Fuß", TotalDiscount.percent(500),
                List.of(item("Webentwicklung", 6000, 8500, 1000, "E", 0), item("Domain", 1000, 1200, 0, "E", 0),
                        item("Anfahrt", 1000, 4000, 0, "E", 0)),
                null, "  PO-4711 ", new ShippingCharge("Versand", 495, null, 0)));

        assertThat(draft.customerReference()).isEqualTo("PO-4711");
        assertThat(draft.shipping()).isEqualTo(new ShippingCharge("Versand", 495, "E", 0));
        assertThat(draft.grossTotalCents()).isEqualTo(49_040);

        var invoice = finalizer.finalizeInvoice(draft.id());
        var xml = new String(read(file(invoice.id(), ArchiveFileType.XML).path()), StandardCharsets.UTF_8);

        assertThat(invoice.grossTotalCents()).isEqualTo(49_040);
        assertThat(invoice.netTotalCents()).isEqualTo(49_040);
        assertThat(invoice.headerText()).isEqualTo("Ref PO-4711");
        assertThat(xml).contains("<ram:BuyerReference>PO-4711</ram:BuyerReference>",
                "<ram:ChargeTotalAmount>4.95</ram:ChargeTotalAmount>");
        assertThat(taxTotalBasis(invoice.id())).isEqualTo(49_040);
        assertValid(read(file(invoice.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void regularInvoiceWithShippingInTheGroupOfAnItemSumsUpAndIsValid() throws Exception {
        var draft = invoices.createDraft(new InvoiceInput(customer(EInvoiceMode.ZUGFERD), LocalDate.of(2027, 2, 1),
                null, null, 14, "Betreff", "Kopf", "Fuß", TotalDiscount.percent(1000),
                List.of(item("Beratung", 1000, 100_000, 0, "S", 1900), item("Unterlagen", 1000, 10_000, 0, "S", 700)),
                null, null, new ShippingCharge("Versand", 1000, "S", 1900)));

        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThat(invoice.netTotalCents()).isEqualTo(100_000);
        assertThat(invoice.taxTotalCents()).isEqualTo(17_920);
        assertThat(invoice.grossTotalCents()).isEqualTo(117_920);
        assertThat(invoice.taxTotals())
                .containsExactlyInAnyOrder(new TaxTotal("S", 1900, 91_000, 17_290), new TaxTotal("S", 700, 9_000, 630));
        assertValid(read(file(invoice.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void cancellationNegatesTheShippingAndCopiesTheReference() throws Exception {
        var invoice = finalizer.finalizeInvoice(invoices.createDraft(new InvoiceInput(customer(EInvoiceMode.ZUGFERD),
                LocalDate.of(2027, 2, 1), null, null, 14, "Betreff", "Kopf", "Fuß", TotalDiscount.percent(1000),
                List.of(item("Beratung", 1000, 100_000, 0, "S", 1900), item("Unterlagen", 1000, 10_000, 0, "S", 700)),
                null, "PO-9", new ShippingCharge("Versand", 1000, "S", 1900))).id());

        var cancellation = finalizer.cancelInvoice(invoice.id());
        var xml = new String(read(file(cancellation.id(), ArchiveFileType.XML).path()), StandardCharsets.UTF_8);

        assertThat(cancellation.shipping()).isEqualTo(new ShippingCharge("Versand", -1000, "S", 1900));
        assertThat(cancellation.customerReference()).isEqualTo("PO-9");
        assertThat(cancellation.netTotalCents()).isEqualTo(-invoice.netTotalCents());
        assertThat(cancellation.grossTotalCents()).isEqualTo(-invoice.grossTotalCents());
        assertThat(xml).contains("<ram:TypeCode>381</ram:TypeCode>", "<ram:ChargeTotalAmount>10.00</ram:ChargeTotalAmount>",
                "<ram:BuyerReference>PO-9</ram:BuyerReference>");
        assertValid(read(file(cancellation.id(), ArchiveFileType.PDF).path()));
    }

    @Test
    void shippingNeedsANameAndAPermittedTaxCategory() {
        var customerId = customer(EInvoiceMode.NONE);

        assertThatThrownBy(() -> invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null, null,
                null, TotalDiscount.none(), List.of(item("Beratung", 1000, 9000, 0, "E", 0)), null, null,
                new ShippingCharge(" ", 495, null, 0))))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.shippingName");
        assertThatThrownBy(() -> invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null, null,
                null, TotalDiscount.none(), List.of(item("Beratung", 1000, 9000, 0, "E", 0)), null, null,
                new ShippingCharge("Versand", 495, "S", 1900))))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxProfileMismatch");
    }

    @Test
    void reviewShowsTheShippingAndReferenceWithoutStoringAnything() {
        var customerId = customer(EInvoiceMode.NONE);
        var draft = draft(customerId, TODAY, item("Beratung", 2000, 9000, 0, "E", 0));
        var input = new InvoiceInput(customerId, TODAY, null, null, 30, "Betreff", null, null, TotalDiscount.none(),
                List.of(item("Beratung", 2000, 9000, 0, "E", 0)), null, "PO-1", new ShippingCharge("Paket", 695, "E", 0));

        var review = finalizer.review(draft.id(), input);

        assertThat(review.customerReference()).isEqualTo("PO-1");
        assertThat(review.shippingName()).isEqualTo("Paket");
        assertThat(review.calculation().shippingCents()).isEqualTo(695);
        assertThat(review.calculation().grossCents()).isEqualTo(18_695);
        assertThat(invoices.find(draft.id()).orElseThrow().shipping().isPresent()).isFalse();
    }

    private long taxTotalBasis(String documentId) {
        return jdbc.sql("SELECT COALESCE(SUM(basis_cents), 0) FROM document_tax_total WHERE document_id = :id")
                .param("id", documentId)
                .query(Long.class)
                .single();
    }

    @Test
    void xrechnungModeIsRejectedUntilItIsSupported() {
        assertThatThrownBy(() -> customer(EInvoiceMode.XRECHNUNG)).isInstanceOf(RuleViolationException.class);
    }

    private String customer(EInvoiceMode mode) {
        return customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE", null,
                null, null, null, null, mode)).id();
    }

    private InvoiceInfo draft(String customerId, LocalDate issueDate, InvoiceItemInput... items) {
        return invoices.createDraft(new InvoiceInput(customerId, issueDate, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.percent(500), List.of(items)));
    }

    private static InvoiceItemInput item(String text, long quantityThousandths, long priceCents, int discountBp,
            String category, int rateBp) {
        return new InvoiceItemInput(text, quantityThousandths, "Std", priceCents, discountBp, category, rateBp, null);
    }

    private ArchivedFile file(String documentId, ArchiveFileType type) {
        return archive.findByOwner(ArchiveOwnerType.DOCUMENT, documentId).stream()
                .filter(file -> file.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private static byte[] read(String path) {
        try {
            return Files.readAllBytes(Path.of("target/test-archive").resolve(path));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertValid(byte[] pdf) {
        var report = new ZUGFeRDValidator().validate(pdf, "invoice.pdf");

        assertThat(report).contains("<xml>")
                .doesNotContain("<error")
                .doesNotContain("<fatal")
                .doesNotContain("status=\"invalid\"");
    }

    private void company(String taxNumber) {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, tax_number, iban, bic, bank_name,
                                     payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        :taxNumber, 'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14, '2026-01-01T00:00:00Z')
                """)
                .param("taxNumber", taxNumber)
                .update();
    }

    private void profile(String id, String from, String to, String regime, int rate, String category, String text) {
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES (:id, 'c1', :from, :to, :regime, :rate, :category, :text)
                """)
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .param("regime", regime)
                .param("rate", rate)
                .param("category", category)
                .param("text", text)
                .update();
    }
}
