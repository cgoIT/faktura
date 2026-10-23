package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.customer.ContactInput;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.render.InvoiceRenderer;
import de.cgoit.faktura.render.RenderModel;
import de.cgoit.faktura.shared.RuleViolationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SqliteTest
class FinalizeServiceTest {

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
    private AuditService audit;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private InvoiceRenderer renderer;

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
        profile("p1", "2020-01-01", "2026-12-31", "SMALL_BUSINESS", 0, "E");
        profile("p2", "2027-01-01", null, "REGULAR", 1900, "S");
        customerId = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, null, null, null, null)).id();
    }

    @AfterEach
    void cleanUp() {
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

    private void profile(String id, String from, String to, String regime, int rate, String category) {
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
                .param("text", "SMALL_BUSINESS".equals(regime) ? "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet." : null)
                .update();
    }

    private InvoiceInfo draft(LocalDate issueDate, LocalDate serviceFrom, LocalDate serviceTo, String category,
            int rateBp) {
        var item = new InvoiceItemInput("Webentwicklung\nStundensatz", 6000, "Std", 8500, 1000, category, rateBp, null);

        return invoices.createDraft(new InvoiceInput(customerId, issueDate, serviceFrom, serviceTo, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.percent(500), List.of(item)));
    }

    private InvoiceInfo smallBusinessDraft() {
        return draft(TODAY, null, null, "E", 0);
    }

    @Test
    void finalizingAssignsNumberStoresTotalsSnapshotsAndArchivesThePdf() throws Exception {
        var draft = smallBusinessDraft();

        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThat(invoice.status()).isEqualTo(DocumentStatus.FINAL);
        assertThat(invoice.number()).isEqualTo("RE202600001");
        assertThat(invoice.grossTotalCents()).isEqualTo(43_605);
        assertThat(invoice.taxTotals()).containsExactly(new TaxTotal("E", 0, 43_605, 0));
        assertThat(invoice.recipientName()).isEqualTo("Muster GmbH");

        var row = jdbc.sql("SELECT sender_snapshot, recipient_snapshot, tax_snapshot, finalized_at FROM document "
                + "WHERE id = :id").param("id", draft.id()).query().singleRow();
        assertThat((String) row.get("sender_snapshot")).contains("Beispiel Nebengewerbe", "Erika Beispiel");
        assertThat((String) row.get("recipient_snapshot")).contains("Muster GmbH");
        assertThat((String) row.get("tax_snapshot")).contains("SMALL_BUSINESS", "§ 19 UStG");
        assertThat(row.get("finalized_at")).isNotNull();

        var files = archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id());
        assertThat(files).hasSize(1);

        var content = Files.readAllBytes(Path.of("target/test-archive").resolve(files.getFirst().path()));
        assertThat(files.getFirst().sha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(files.getFirst().path()).isEqualTo("2026/RE202600001.pdf");

        try (var pdf = Loader.loadPDF(content)) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("RE202600001", "Muster GmbH", "Gemäß § 19 UStG")
                    .doesNotContain("Betreff");
        }

        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, draft.id())).containsExactly(AuditAction.FINALIZE);
        assertThat(finalizer.findPdf(draft.id())).isPresent();
    }

    @Test
    void numbersAreGapFreeAcrossInvoices() {
        var first = finalizer.finalizeInvoice(smallBusinessDraft().id());
        var second = finalizer.finalizeInvoice(smallBusinessDraft().id());

        assertThat(first.number()).isEqualTo("RE202600001");
        assertThat(second.number()).isEqualTo("RE202600002");
    }

    @Test
    void failingRenderRollsBackAndTheNumberStaysFree() {
        var draft = smallBusinessDraft();
        doThrow(new IllegalStateException("render failed")).when(renderer).renderPdf(any(RenderModel.class));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id())).isInstanceOf(IllegalStateException.class);

        assertThat(invoices.find(draft.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(invoices.find(draft.id()).orElseThrow().number()).isNull();
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id())).isEmpty();
        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, draft.id())).isEmpty();

        reset(renderer);

        assertThat(finalizer.finalizeInvoice(draft.id()).number()).isEqualTo("RE202600001");
    }

    @Test
    void finalizedInvoiceCannotBeChangedDeletedOrFinalizedAgain() {
        var invoice = finalizer.finalizeInvoice(smallBusinessDraft().id());
        var input = new InvoiceInput(customerId, TODAY, null, null, 14, "Neu", null, null, null, List.of());

        assertThatThrownBy(() -> invoices.updateDraft(invoice.id(), input)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> invoices.deleteDraft(invoice.id())).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> finalizer.finalizeInvoice(invoice.id())).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void laterChangesToCustomerAndCompanyDoNotChangeTheFinalizedInvoice() throws Exception {
        var invoice = finalizer.finalizeInvoice(smallBusinessDraft().id());
        var fileBefore = archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()).getFirst();

        jdbc.sql("UPDATE customer SET name = 'Umbenannt AG'").update();
        jdbc.sql("UPDATE company SET name = 'Andere Firma'").update();

        var after = invoices.find(invoice.id()).orElseThrow();

        assertThat(after.recipientName()).isEqualTo("Muster GmbH");
        assertThat(archive.read(fileBefore)).isNotEmpty();
        assertThat(jdbc.sql("SELECT sender_snapshot FROM document WHERE id = :id").param("id", invoice.id())
                .query(String.class).single()).contains("Beispiel Nebengewerbe");
    }

    @Test
    void taxProfileOfTheServiceDateIsUsedNotTheIssueDate() {
        var invoice = finalizer.finalizeInvoice(draft(LocalDate.of(2027, 1, 10), LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31), "E", 0).id());

        assertThat(jdbc.sql("SELECT tax_snapshot FROM document WHERE id = :id").param("id", invoice.id())
                .query(String.class).single()).contains("SMALL_BUSINESS");
    }

    @Test
    void regularProfileFinalizesWithTaxLines() {
        var invoice = finalizer.finalizeInvoice(draft(LocalDate.of(2027, 2, 1), null, null, "S", 1900).id());

        assertThat(invoice.taxTotalCents()).isEqualTo(8_285);
        assertThat(invoice.grossTotalCents()).isEqualTo(51_890);
    }

    @Test
    void servicePeriodAcrossAProfileChangeIsRejected() {
        var draft = draft(LocalDate.of(2027, 1, 10), LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31), "E", 0);

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.servicePeriodTaxChange");
    }

    @Test
    void itemWithTaxUnderATaxFreeProfileIsRejected() {
        var draft = draft(LocalDate.of(2027, 2, 1), null, null, "S", 1900);
        jdbc.sql("UPDATE tax_profile SET default_rate_bp = 0, default_category = 'E' WHERE id = 'p2'").update();

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxProfileMismatch");
    }

    @Test
    void invoiceWithoutItemsCannotBeFinalized() {
        var empty = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null, null, null, null,
                List.of()));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(empty.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.noItems");
    }

    @Test
    void cancellationIsANewNumberedDocumentWithNegatedAmountsAndLeavesTheInvoiceUnchanged() {
        var invoice = finalizer.finalizeInvoice(smallBusinessDraft().id());

        var cancellation = finalizer.cancelInvoice(invoice.id());

        assertThat(cancellation.type()).isEqualTo(DocumentType.CANCELLATION);
        assertThat(cancellation.status()).isEqualTo(DocumentStatus.FINAL);
        assertThat(cancellation.number()).isEqualTo("ST202600001");
        assertThat(cancellation.refDocumentId()).isEqualTo(invoice.id());
        assertThat(cancellation.grossTotalCents()).isEqualTo(-43_605);
        assertThat(cancellation.items().getFirst().lineTotalCents()).isEqualTo(-45_900);
        assertThat(cancellation.recipientName()).isEqualTo("Muster GmbH");

        var original = invoices.find(invoice.id()).orElseThrow();
        assertThat(original.status()).isEqualTo(DocumentStatus.FINAL);
        assertThat(original.isCancelled()).isTrue();
        assertThat(original.grossTotalCents()).isEqualTo(43_605);
        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, invoice.id()))
                .containsExactly(AuditAction.FINALIZE, AuditAction.CANCEL);
        assertThat(finalizer.findPdf(cancellation.id())).isPresent();
    }

    @Test
    void invoiceCanBeCancelledOnlyOnceAndDraftsNotAtAll() {
        var invoice = finalizer.finalizeInvoice(smallBusinessDraft().id());
        finalizer.cancelInvoice(invoice.id());
        var draft = smallBusinessDraft();

        assertThatThrownBy(() -> finalizer.cancelInvoice(invoice.id()))
                .hasMessage("document.error.alreadyCancelled");
        assertThatThrownBy(() -> finalizer.cancelInvoice(draft.id()))
                .hasMessage("document.error.notCancellable");
    }

    @Test
    void cancellationNumbersAreGapFreeAndFailureLeavesThemFree() {
        var first = finalizer.finalizeInvoice(smallBusinessDraft().id());
        var second = finalizer.finalizeInvoice(smallBusinessDraft().id());
        doThrow(new IllegalStateException("render failed")).when(renderer).renderPdf(any(RenderModel.class));

        assertThatThrownBy(() -> finalizer.cancelInvoice(first.id())).isInstanceOf(IllegalStateException.class);
        reset(renderer);

        assertThat(invoices.find(first.id()).orElseThrow().isCancelled()).isFalse();
        assertThat(finalizer.cancelInvoice(first.id()).number()).isEqualTo("ST202600001");
        assertThat(finalizer.cancelInvoice(second.id()).number()).isEqualTo("ST202600002");
    }

    @Test
    void previewOfADraftIsAPdfWithoutNumberAndStoresNothing() throws Exception {
        var draft = smallBusinessDraft();
        var input = new InvoiceInput(customerId, TODAY, null, null, 14, "Aus dem Formular", null, null,
                TotalDiscount.none(), List.of(new InvoiceItemInput("Neu im Formular", 1000, "Std", 5000, 0, "E", 0,
                        null)));

        var pdf = finalizer.previewDraft(draft.id(), input);

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Muster GmbH", "Neu im Formular");
        }

        assertThat(invoices.find(draft.id()).orElseThrow().number()).isNull();
        assertThat(invoices.find(draft.id()).orElseThrow().subject()).isEqualTo("Betreff");
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, draft.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM number_sequence WHERE scope = 'INVOICE'").query(Long.class).single()).isZero();
    }

    @Test
    void placeholdersAreFilledInWhenFinalizingAndTheFinalTextIsStored() throws Exception {
        var contact = customers.addContact(customerId, new ContactInput("MR", "Frank", "Kirsch", null, null, null, null));
        var item = new InvoiceItemInput("Arbeit", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 18), 14, "intern",
                "{anrede}\n\nRechnung {rechnungsnummer} für {kunde} ({leistungszeitraum}), zahlbar bis {faelligkeit}.",
                "Betrag {betrag} in {zahlungsziel} Tagen. Ansprechpartner: {ansprechpartner}",
                TotalDiscount.none(), List.of(item), contact.id()));

        var invoice = finalizer.finalizeInvoice(draft.id());

        var expectedHeader = "Sehr geehrter Herr Kirsch,\n\nRechnung RE202600001 für Muster GmbH "
                + "(14.08.2026 - 18.09.2026), zahlbar bis 03.11.2026.";
        assertThat(invoice.headerText()).isEqualTo(expectedHeader);
        assertThat(invoice.footerText()).isEqualTo("Betrag 100,00 € in 14 Tagen. Ansprechpartner: Frank Kirsch");
        assertThat(jdbc.sql("SELECT recipient_snapshot FROM document WHERE id = :id").param("id", invoice.id())
                .query(String.class).single()).contains("Frank Kirsch", "MR");

        var pdf = finalizer.findPdf(invoice.id()).orElseThrow();

        try (var document = Loader.loadPDF(pdf.content())) {
            assertThat(new PDFTextStripper().getText(document)).contains("Sehr geehrter Herr Kirsch,", "Frank Kirsch")
                    .doesNotContain("{anrede}");
        }
    }

    @Test
    void defaultFooterPrintsPaymentDetailsFromTheCompanyPlaceholders() throws Exception {
        var item = new InvoiceItemInput("Arbeit", 1000, "Std", 10_000, 0, "E", 0, null);
        var footer = "Bitte überweisen Sie {betrag} auf {firma_iban} bei der {firma_bank}, BIC {firma_bic}, Kontoinhaber "
                + "{firma_kontoinhaber}. Zahlbar innerhalb von {zahlungsziel} Tagen bis {faelligkeit}.";
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null, null, footer,
                TotalDiscount.none(), List.of(item)));

        var invoice = finalizer.finalizeInvoice(draft.id());

        assertThat(invoice.footerText()).isEqualTo("Bitte überweisen Sie 100,00 € auf DE02 1203 0000 0000 2020 51 "
                + "bei der Testbank, BIC BYLADEM1001, Kontoinhaber Erika Beispiel. Zahlbar innerhalb von 14 Tagen "
                + "bis 03.11.2026.");

        try (var document = Loader.loadPDF(finalizer.findPdf(invoice.id()).orElseThrow().content())) {
            assertThat(new PDFTextStripper().getText(document)).contains("DE02 1203 0000 0000 2020 51")
                    .contains("Kontoinhaber Erika Beispiel");
        }
    }

    @Test
    void markupCharactersInPlaceholderValuesArePrintedLiterally() throws Exception {
        jdbc.sql("UPDATE customer SET name = 'Müller *Söhne* __GmbH__'").update();
        var item = new InvoiceItemInput("Arbeit", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null,
                "Rechnung für **{kunde}**", null, TotalDiscount.none(), List.of(item)));

        var invoice = finalizer.finalizeInvoice(draft.id());

        try (var document = Loader.loadPDF(finalizer.findPdf(invoice.id()).orElseThrow().content())) {
            assertThat(new PDFTextStripper().getText(document)).contains("Müller *Söhne* __GmbH__");
        }
    }

    @Test
    void unknownPlaceholdersAndForeignContactsAreRejectedWhenSaving() {
        var other = customers.create(new CustomerInput("Andere GmbH", null, null, null, "DE", null, null, null, null,
                null));
        var foreign = customers.addContact(other.id(), new ContactInput("Erika", "Beispiel"));
        var item = new InvoiceItemInput("Arbeit", 1000, "Std", 10_000, 0, "E", 0, null);

        assertThatThrownBy(() -> invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null,
                "Hallo {vorname}", null, TotalDiscount.none(), List.of(item))))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("text.error.unknownPlaceholder");
        assertThatThrownBy(() -> invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null,
                null, null, TotalDiscount.none(), List.of(item), foreign.id())))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.contact");
    }

    @Test
    void withoutContactTheSalutationIsGeneric() {
        var item = new InvoiceItemInput("Arbeit", 1000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, null, "{anrede}", null,
                TotalDiscount.none(), List.of(item)));

        assertThat(finalizer.finalizeInvoice(draft.id()).headerText()).isEqualTo("Sehr geehrte Damen und Herren,");
    }

    @Test
    void numbersWithUnsafePathCharactersGetDistinctArchivePaths() {
        jdbc.sql("UPDATE number_pattern SET pattern = 'RE/{jahr}/{#####}' WHERE scope = 'INVOICE'").update();

        try {
            var invoice = finalizer.finalizeInvoice(smallBusinessDraft().id());
            var path = archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()).getFirst().path();

            assertThat(invoice.number()).isEqualTo("RE/2026/00001");
            assertThat(path).startsWith("2026/RE_2026_00001-").endsWith(".pdf");
            assertThat(finalizer.findPdf(invoice.id())).isPresent();
        } finally {
            jdbc.sql("UPDATE number_pattern SET pattern = 'RE{jahr}{#####}' WHERE scope = 'INVOICE'").update();
        }
    }
}
