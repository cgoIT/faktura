package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.render.InvoiceRenderer;
import de.cgoit.faktura.render.RenderModel;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SqliteTest
class OfferServiceTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 10, 20);

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private OfferService offers;

    @Autowired
    private CustomerService customers;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private AuditService audit;

    @Autowired
    private PaymentService payments;

    @Autowired
    private InvoiceLetterService letters;

    @Autowired
    private AccountingExportService export;

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
                                     tax_number, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14, '12/345/67890',
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG ...')
                """).update();
        customerId = customer(EInvoiceMode.NONE);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM offer_event").update();
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

    private String customer(EInvoiceMode mode) {
        return customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE", null,
                null, null, null, null, mode)).id();
    }

    private InvoiceInput input(String customer, LocalDate validUntil) {
        var item = new InvoiceItemInput("Webentwicklung", 6000, "Std", 8500, 0, "E", 0, null);

        return new InvoiceInput(customer, ISSUED, null, null, null, "Relaunch", "Kopf", "Fuß", null, List.of(item),
                null, "ANF-7", null, validUntil);
    }

    private InvoiceInfo offerDraft() {
        return invoices.createOfferDraft(input(customerId, null));
    }

    @Test
    void anOfferDraftHasAValidityDateFromTheCompanyDefaultAndNoDueDate() {
        var draft = offerDraft();

        assertThat(draft.type()).isEqualTo(DocumentType.OFFER);
        assertThat(draft.validUntil()).isEqualTo(ISSUED.plusDays(30));
        assertThat(draft.dueDate()).isNull();
        assertThat(draft.customerReference()).isEqualTo("ANF-7");
    }

    @Test
    void theDefaultValidityComesFromTheCompanySetting() {
        jdbc.sql("UPDATE company SET offer_valid_days = 14").update();

        assertThat(offerDraft().validUntil()).isEqualTo(ISSUED.plusDays(14));
    }

    @Test
    void aGivenValidityDateIsKeptButNotBeforeTheIssueDate() {
        var draft = invoices.createOfferDraft(input(customerId, ISSUED.plusDays(7)));

        assertThat(draft.validUntil()).isEqualTo(ISSUED.plusDays(7));

        assertThatThrownBy(() -> invoices.createOfferDraft(input(customerId, ISSUED.minusDays(1))))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("offer.error.validUntil");
    }

    @Test
    void updatingADraftKeepsItAnOffer() {
        var draft = offerDraft();

        var updated = invoices.updateDraft(draft.id(), input(customerId, ISSUED.plusDays(3)), draft.version());

        assertThat(updated.type()).isEqualTo(DocumentType.OFFER);
        assertThat(updated.validUntil()).isEqualTo(ISSUED.plusDays(3));
    }

    @Test
    void finalizingTakesTheNumberFromTheOfferRangeAndArchivesAPdf() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());

        assertThat(offer.status()).isEqualTo(DocumentStatus.FINAL);
        assertThat(offer.number()).isEqualTo("AN202600001");
        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, offer.id()))
                .extracting(file -> file.type())
                .containsExactly(ArchiveFileType.PDF);

        var invoice = finalizer.finalizeInvoice(invoices.createDraft(input(customerId, null)).id());

        assertThat(invoice.number()).isEqualTo("RE202600001");
        assertThat(finalizer.finalizeInvoice(offerDraft().id()).number()).isEqualTo("AN202600002");
    }

    @Test
    void anOfferGetsNoEInvoiceEvenForACustomerWithZugferd() {
        var zugferd = customer(EInvoiceMode.ZUGFERD);

        var offer = finalizer.finalizeInvoice(invoices.createOfferDraft(input(zugferd, null)).id());

        assertThat(archive.findByOwner(ArchiveOwnerType.DOCUMENT, offer.id()))
                .extracting(file -> file.type())
                .containsExactly(ArchiveFileType.PDF);
    }

    @Test
    void reviewOfAnOfferShowsTheValidityAndSkipsTheEInvoiceTrialRun() {
        var zugferd = customer(EInvoiceMode.ZUGFERD);
        var draft = invoices.createOfferDraft(input(zugferd, null));

        var review = finalizer.review(draft.id(), input(zugferd, null));

        assertThat(review.validUntil()).isEqualTo(ISSUED.plusDays(30));
        assertThat(review.dueDate()).isNull();
        assertThat(review.einvoiceFailed()).isFalse();
    }

    @Test
    void failingRenderRollsBackAndTheOfferNumberStaysFree() {
        var draft = offerDraft();
        doThrow(new IllegalStateException("render failed")).when(renderer).renderPdf(any(RenderModel.class));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(draft.id())).isInstanceOf(IllegalStateException.class);

        assertThat(invoices.find(draft.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);

        reset(renderer);

        assertThat(finalizer.finalizeInvoice(draft.id()).number()).isEqualTo("AN202600001");
    }

    @Test
    void aFinalizedOfferCannotBeChangedDeletedOrCancelled() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());

        assertThatThrownBy(() -> invoices.updateDraft(offer.id(), input(customerId, null)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("document.error.notDraft");
        assertThatThrownBy(() -> invoices.deleteDraft(offer.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("document.error.notDraft");
        assertThatThrownBy(() -> finalizer.cancelInvoice(offer.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("document.error.notCancellable");
    }

    @Test
    void aFinalOfferTakesNoPaymentsAndGivesNoDunningBasisOrExportRow() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());
        var payment = new PaymentInput(LocalDate.now(), 1000, PaymentMethod.BANK_TRANSFER, null, null);

        assertThatThrownBy(() -> payments.record(offer.id(), payment)).isInstanceOf(RuleViolationException.class);
        assertThat(letters.dunningBasis(offer.id())).isEmpty();
        assertThat(invoices.listOverdue()).isEmpty();
        assertThat(export.invoices(ISSUED.getYear())).isEmpty();
    }

    @Test
    void anOfferDraftCanBeDeleted() {
        var draft = offerDraft();

        invoices.deleteDraft(draft.id());

        assertThat(invoices.find(draft.id())).isEmpty();
    }

    @Test
    void anOfferWithoutItemsCannotBeFinalized() {
        var empty = invoices.createOfferDraft(new InvoiceInput(customerId, ISSUED, null, null, null, null, null, null,
                null, List.of()));

        assertThatThrownBy(() -> finalizer.finalizeInvoice(empty.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("offer.error.noItems");
    }

    @Test
    void offersAndInvoicesAreListedApart() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());
        var invoice = invoices.createDraft(input(customerId, null));

        assertThat(offers.list("", null)).extracting(OfferSummary::id).containsExactly(offer.id());
        assertThat(invoices.list("", null)).extracting(InvoiceSummary::id).containsExactly(invoice.id());
    }

    @Test
    void theListShowsTheDerivedStateAndFiltersByIt() {
        var open = finalizer.finalizeInvoice(offerDraft().id());
        var expired = finalizer.finalizeInvoice(invoices.createOfferDraft(input(customerId, ISSUED)).id());
        var draft = offerDraft();

        assertThat(offers.list("", OfferState.OPEN)).extracting(OfferSummary::id).containsExactly(open.id());
        assertThat(offers.list("", OfferState.EXPIRED)).extracting(OfferSummary::id).containsExactly(expired.id());
        assertThat(offers.list("", OfferState.DRAFT)).extracting(OfferSummary::id).containsExactly(draft.id());
        assertThat(offers.state(open.id())).contains(OfferState.OPEN);
        assertThat(offers.state(invoices.createDraft(input(customerId, null)).id())).isEmpty();
    }

    @Test
    void rejectingRecordsAnEventAndTheStateFollowsWithoutChangingTheOffer() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());

        var event = offers.record(offer.id(), OfferEventType.REJECTED);

        assertThat(event.type()).isEqualTo(OfferEventType.REJECTED);
        assertThat(offers.state(offer.id())).contains(OfferState.REJECTED);
        assertThat(offers.events(offer.id())).extracting(OfferEvent::type).containsExactly(OfferEventType.REJECTED);
        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, offer.id())).contains(AuditAction.OFFER_EVENT);
        assertThat(invoices.find(offer.id()).orElseThrow().status()).isEqualTo(DocumentStatus.FINAL);
        assertThatThrownBy(() -> offers.record(offer.id(), OfferEventType.REJECTED))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("offer.error.alreadyDecided");
    }

    @Test
    void eventsCanOnlyBeRecordedForAFinalizedOffer() {
        var draft = offerDraft();
        var invoice = invoices.createDraft(input(customerId, null));

        assertThatThrownBy(() -> offers.record(draft.id(), OfferEventType.SENT))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("offer.error.notFinal");
        assertThatThrownBy(() -> offers.record(invoice.id(), OfferEventType.REJECTED))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("offer.error.notFinal");
    }

    @Test
    void creatingAnInvoiceCopiesTheContentLinksTheOfferAndRecordsAcceptance() {
        var discounted = new InvoiceInput(customerId, ISSUED, null, null, null, "Relaunch", "Kopf", "Fuß",
                TotalDiscount.percent(500), List.of(new InvoiceItemInput("Webentwicklung", 6000, "Std", 8500, 1000, "E",
                0, null)), null, "ANF-7", new ShippingCharge("Porto", 495, null, 0), null);
        var offer = finalizer.finalizeInvoice(invoices.createOfferDraft(discounted).id());

        var draft = offers.createInvoiceDraft(offer.id());
        var stored = invoices.find(draft.id()).orElseThrow();

        assertThat(stored.type()).isEqualTo(DocumentType.INVOICE);
        assertThat(stored.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(stored.refDocumentId()).isEqualTo(offer.id());
        assertThat(stored.dueDate()).isNotNull();
        assertThat(stored.validUntil()).isNull();
        assertThat(stored.customerId()).isEqualTo(customerId);
        assertThat(stored.customerReference()).isEqualTo("ANF-7");
        assertThat(stored.totalDiscount()).isEqualTo(TotalDiscount.percent(500));
        assertThat(stored.shipping().cents()).isEqualTo(495);
        assertThat(stored.items()).hasSize(1);
        assertThat(stored.items().getFirst().description()).isEqualTo("Webentwicklung");
        assertThat(stored.items().getFirst().discountBp()).isEqualTo(1000);
        assertThat(stored.grossTotalCents()).isEqualTo(offer.grossTotalCents());
        assertThat(offers.invoiceIds(offer.id())).containsExactly(draft.id());
        assertThat(offers.state(offer.id())).contains(OfferState.ACCEPTED);
        assertThat(invoices.find(offer.id()).orElseThrow().status()).isEqualTo(DocumentStatus.FINAL);
    }

    @Test
    void aSecondInvoiceFromTheSameOfferIsPossibleAndAcceptanceIsRecordedOnce() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());

        var first = offers.createInvoiceDraft(offer.id());
        var second = offers.createInvoiceDraft(offer.id());

        assertThat(offers.invoiceIds(offer.id())).containsExactly(first.id(), second.id());
        assertThat(offers.events(offer.id())).extracting(OfferEvent::type)
                .containsExactly(OfferEventType.ACCEPTED);
    }

    @Test
    void anInvoiceCannotBeCreatedFromADraftOrAnInvoice() {
        var draft = offerDraft();
        var invoice = finalizer.finalizeInvoice(invoices.createDraft(input(customerId, null)).id());

        assertThatThrownBy(() -> offers.createInvoiceDraft(draft.id())).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> offers.createInvoiceDraft(invoice.id())).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void expiringSoonListsUndecidedOffersEndingWithinTheConfiguredDays() {
        var today = LocalDate.now();
        var soon = finalizer.finalizeInvoice(invoices.createOfferDraft(offerInput(today.plusDays(7))).id());
        var tomorrow = finalizer.finalizeInvoice(invoices.createOfferDraft(offerInput(today.plusDays(1))).id());
        var later = finalizer.finalizeInvoice(invoices.createOfferDraft(offerInput(today.plusDays(8))).id());
        var rejected = finalizer.finalizeInvoice(invoices.createOfferDraft(offerInput(today.plusDays(2))).id());
        var draft = invoices.createOfferDraft(offerInput(today.plusDays(2)));

        offers.record(rejected.id(), OfferEventType.REJECTED);
        offers.record(tomorrow.id(), OfferEventType.SENT);

        assertThat(offers.expiringDays()).isEqualTo(7);
        assertThat(offers.expiringSoon()).extracting(OfferSummary::id).containsExactly(tomorrow.id(), soon.id());
        assertThat(offers.expiringSoon()).extracting(OfferSummary::id).doesNotContain(later.id(), draft.id(),
                rejected.id());
    }

    private InvoiceInput offerInput(LocalDate validUntil) {
        return new InvoiceInput(customerId, LocalDate.now(), null, null, null, "Relaunch", "Kopf", "Fuß", null,
                List.of(new InvoiceItemInput("Webentwicklung", 6000, "Std", 8500, 0, "E", 0, null)), null, null, null,
                validUntil);
    }

    @Test
    void theArchivedPdfCanBeReadBack() {
        var offer = finalizer.finalizeInvoice(offerDraft().id());

        assertThat(finalizer.findPdf(offer.id())).isPresent()
                .get()
                .satisfies(pdf -> assertThat(pdf.filename()).isEqualTo("AN202600001.pdf"));
    }
}
