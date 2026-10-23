package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.internal.DocumentItemRepository;
import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class InvoiceServiceTest {

    private static final String HOUR = "00000000-0000-0000-0000-000000000001";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentItemRepository items;

    private String customerId;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '2026-01-01T00:00:00Z')
                """).update();
        profile("p1", "2020-01-01", "2026-12-31", "SMALL_BUSINESS", 0, "E");
        profile("p2", "2027-01-01", null, "REGULAR", 1900, "S");
        customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null)).id();
    }

    @Test
    void listIsSortedByInvoiceDateThenNumberDescending() {
        insertDocument("d1", "RE026001", "2026-01-20");
        insertDocument("d2", "RE026002", "2026-01-20");
        insertDocument("d3", "RE025008", "2025-12-22");
        insertDocument("d4", null, "2026-01-20");

        var order = invoices.list("", null).stream()
                .map(InvoiceSummary::id)
                .toList();

        assertThat(order).containsExactly("d4", "d2", "d1", "d3");
    }

    private void insertDocument(String id, String number, String issueDate) {
        jdbc.sql("""
                INSERT INTO document (id, type, status, number, customer_id, issue_date, created_at, updated_at)
                VALUES (:id, 'INVOICE', :status, :number, :customer, :issueDate, '2026-10-20T00:00:00Z',
                        '2026-10-20T00:00:00Z')
                """)
                .param("id", id)
                .param("status", number == null ? "DRAFT" : "FINAL")
                .param("number", number)
                .param("customer", customerId)
                .param("issueDate", issueDate)
                .update();
    }

    private void profile(String id, String from, String to, String regime, int rate, String category) {
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES (:id, 'c1', :from, :to, :regime, :rate, :category)
                """)
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .param("regime", regime)
                .param("rate", rate)
                .param("category", category)
                .update();
    }

    private InvoiceItemInput item(String description, long quantity, long price, int discountBp) {
        return new InvoiceItemInput(description, quantity, "Std", price, discountBp, "E", 0, null);
    }

    private InvoiceInput input(List<InvoiceItemInput> items, TotalDiscount discount) {
        return new InvoiceInput(customerId, TODAY, null, null, 14, "Webentwicklung Oktober 2026", "Kopf", "Fuß",
                discount, items);
    }

    private InvoiceInput exampleInput() {
        return input(List.of(item("Webentwicklung", 6000, 8500, 1000), item("Domain", 1000, 1200, 0),
                item("Anfahrt", 1000, 4000, 0)), TotalDiscount.percent(500));
    }

    @Test
    void draftHasNoNumberAndNoSnapshotsAndLeavesNumberingUntouched() {
        var draft = invoices.createDraft(exampleInput());

        assertThat(draft.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(draft.number()).isNull();
        assertThat(draft.dueDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(jdbc.sql("SELECT sender_snapshot || recipient_snapshot || tax_snapshot FROM document")
                .query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM number_sequence WHERE scope = 'INVOICE'").query(Long.class)
                .single()).isZero();
    }

    @Test
    void storesCalculatedTotalsOfTheExample() {
        var draft = invoices.createDraft(exampleInput());

        assertThat(draft.netTotalCents()).isEqualTo(48_545);
        assertThat(draft.taxTotalCents()).isZero();
        assertThat(draft.grossTotalCents()).isEqualTo(48_545);
        assertThat(draft.taxTotals()).containsExactly(new TaxTotal("E", 0, 48_545, 0));
        assertThat(draft.items()).extracting(InvoiceItemInfo::lineTotalCents).containsExactly(45_900L, 1_200L, 4_000L);
        assertThat(draft.items()).extracting(InvoiceItemInfo::position).containsExactly(1, 2, 3);
        assertThat(draft.totalDiscount()).isEqualTo(TotalDiscount.percent(500));
    }

    @Test
    void catalogValuesAreCopiedAndLaterCatalogChangesDoNotTouchTheItem() {
        var entry = catalog.create(new CatalogItemInput("Webentwicklung", "Stundensatz", HOUR, 8500, null));
        var proposal = invoices.proposeItem(entry.id(), TODAY);

        var draft = invoices.createDraft(input(List.of(proposal), TotalDiscount.none()));
        catalog.update(entry.id(), new CatalogItemInput("Umbenannt", null, HOUR, 9900, "Z"));

        var stored = invoices.find(draft.id()).orElseThrow().items().getFirst();
        assertThat(stored.description()).isEqualTo("Webentwicklung\nStundensatz");
        assertThat(stored.unitPriceCents()).isEqualTo(8500);
        assertThat(stored.unitCode()).isEqualTo("Std");
        assertThat(stored.unitName()).isEqualTo("Stunde");
        assertThat(stored.unitNamePlural()).isEqualTo("Stunden");
        assertThat(stored.unitUneceCode()).isEqualTo("HUR");
        assertThat(stored.taxCategory()).isEqualTo("E");
        assertThat(stored.catalogItemId()).isEqualTo(entry.id());
    }

    @Test
    void proposedTaxComesFromTheProfileValidOnTheServiceDay() {
        var small = invoices.proposeItem(null, LocalDate.of(2026, 12, 31));
        var regular = invoices.proposeItem(null, LocalDate.of(2027, 1, 1));

        assertThat(small.taxCategory()).isEqualTo("E");
        assertThat(small.taxRateBp()).isZero();
        assertThat(regular.taxCategory()).isEqualTo("S");
        assertThat(regular.taxRateBp()).isEqualTo(1900);
    }

    @Test
    void catalogCategoryWithoutRateIsTaxedWithZeroEvenUnderRegularProfile() {
        var entry = catalog.create(new CatalogItemInput("Export", null, HOUR, 1000, "Z"));

        var proposal = invoices.proposeItem(entry.id(), LocalDate.of(2027, 2, 1));

        assertThat(proposal.taxCategory()).isEqualTo("Z");
        assertThat(proposal.taxRateBp()).isZero();
    }

    @Test
    void proposingWithoutValidProfileFails() {
        assertThatThrownBy(() -> invoices.proposeItem(null, LocalDate.of(2019, 1, 1)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.noTaxProfile");
    }

    @Test
    void taxRateIsRejectedForACategoryWithoutRate() {
        var item = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "E", 1900, null);

        assertThatThrownBy(() -> invoices.createDraft(input(List.of(item), TotalDiscount.none())))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxRateNotAllowed");
    }

    @Test
    void taxInAnItemIsRejectedWhileEditingWhenTheProfileChargesNoTax() {
        var taxed = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "S", 1900, null);
        var smallBusinessInput = input(List.of(taxed), TotalDiscount.none());

        assertThatThrownBy(() -> invoices.createDraft(smallBusinessInput))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxProfileMismatch");
        assertThatThrownBy(() -> invoices.calculate(smallBusinessInput))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxProfileMismatch");
    }

    @Test
    void categoryOutsideTheProfileIsRejectedAndAllowedCategoriesAreOffered() {
        jdbc.sql("UPDATE tax_profile SET allowed_categories = 'E' WHERE id = 'p1'").update();
        var zeroRated = new InvoiceItemInput("Export", 1000, "Std", 10_000, 0, "Z", 0, null);

        assertThatThrownBy(() -> invoices.createDraft(input(List.of(zeroRated), TotalDiscount.none())))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.categoryNotAllowed");
        assertThat(invoices.allowedCategories(TODAY)).containsExactly("E");
        assertThat(invoices.allowedCategories(LocalDate.of(2027, 2, 1))).hasSizeGreaterThan(5);
    }

    @Test
    void ratedCategoryNeedsARateAboveZero() {
        var noRate = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "S", 0, null);
        var input = new InvoiceInput(customerId, LocalDate.of(2027, 2, 1), null, null, 14, null, null, null,
                TotalDiscount.none(), List.of(noRate));

        assertThatThrownBy(() -> invoices.calculate(input))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.taxRateRequired");
    }

    @Test
    void catalogChoicesLeaveOutEntriesWhoseCategoryTheProfileDoesNotAllow() {
        jdbc.sql("UPDATE tax_profile SET allowed_categories = 'E' WHERE id = 'p1'").update();
        var plain = catalog.create(new CatalogItemInput("Webentwicklung", null, HOUR, 8500, null));
        var exempt = catalog.create(new CatalogItemInput("Schulung", null, HOUR, 9000, "E"));
        var export = catalog.create(new CatalogItemInput("Export", null, HOUR, 1000, "Z"));

        var small = invoices.catalogChoices(TODAY);
        var regular = invoices.catalogChoices(LocalDate.of(2027, 2, 1));

        assertThat(small.items()).extracting(entry -> entry.id()).containsExactlyInAnyOrder(plain.id(), exempt.id());
        assertThat(small.hidden()).isEqualTo(1);
        assertThat(regular.items()).hasSize(3);
        assertThat(regular.hidden()).isZero();
        assertThatThrownBy(() -> invoices.proposeItem(export.id(), TODAY))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.categoryNotAllowed");
    }

    @Test
    void catalogChoicesFilterByQueryAndListOnlyTheFirstEntries() {
        for (var number = 1; number <= 5; number++) {
            catalog.create(new CatalogItemInput("Beratung " + number, null, HOUR, 9000, null));
        }

        catalog.create(new CatalogItemInput("Hosting", "Für die Website", HOUR, 1500, null));

        var some = invoices.catalogChoices(TODAY, "BERATUNG", 3);
        var byDescription = invoices.catalogChoices(TODAY, "website", 3);
        var all = invoices.catalogChoices(TODAY, " ", 3);

        assertThat(some.items()).hasSize(3);
        assertThat(some.matches()).isEqualTo(5);
        assertThat(some.more()).isEqualTo(2);
        assertThat(byDescription.items()).extracting(entry -> entry.name()).containsExactly("Hosting");
        assertThat(all.matches()).isEqualTo(6);
    }

    @Test
    void catalogChoicesCountMatchesAndHiddenEntriesTogetherWithTheLimit() {
        jdbc.sql("UPDATE tax_profile SET allowed_categories = 'E' WHERE id = 'p1'").update();
        catalog.create(new CatalogItemInput("Beratung A", null, HOUR, 9000, null));
        catalog.create(new CatalogItemInput("Beratung B", null, HOUR, 9000, null));
        catalog.create(new CatalogItemInput("Beratung C", null, HOUR, 9000, "E"));
        catalog.create(new CatalogItemInput("Beratung Export", null, HOUR, 9000, "Z"));
        catalog.create(new CatalogItemInput("Hosting", null, HOUR, 1500, null));

        var choices = invoices.catalogChoices(TODAY, "beratung", 2);

        assertThat(choices.items()).extracting(entry -> entry.name()).containsExactly("Beratung A", "Beratung B");
        assertThat(choices.matches()).isEqualTo(3);
        assertThat(choices.more()).isEqualTo(1);
        assertThat(choices.hidden()).isEqualTo(1);
    }

    @Test
    void regularTaxIsCalculatedFromTheItemRates() {
        var regular = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "S", 1900, null);
        var regularInput = new InvoiceInput(customerId, LocalDate.of(2027, 2, 1), null, null, 14, null, null, null,
                TotalDiscount.none(), List.of(regular));

        var draft = invoices.createDraft(regularInput);

        assertThat(draft.taxTotalCents()).isEqualTo(1_900);
        assertThat(draft.grossTotalCents()).isEqualTo(11_900);
    }

    @Test
    void customerDefaultsFillPaymentTermAndDiscount() {
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, 30, 500,
                null));
        var withDefaults = new InvoiceInput(customer.id(), TODAY, null, null, null, null, null, null, null,
                List.of(item("Beratung", 1000, 10_000, 0)));

        var draft = invoices.createDraft(withDefaults);

        assertThat(draft.dueDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(draft.totalDiscount()).isEqualTo(TotalDiscount.percent(500));
        assertThat(draft.netTotalCents()).isEqualTo(9_500);
    }

    @Test
    void companyPaymentTermIsUsedWhenCustomerHasNone() {
        var withoutTerm = new InvoiceInput(customerId, TODAY, null, null, null, null, null, null,
                TotalDiscount.none(), List.of());

        assertThat(invoices.createDraft(withoutTerm).dueDate()).isEqualTo(TODAY.plusDays(14));
    }

    @Test
    void updateReplacesTheItems() {
        var draft = invoices.createDraft(exampleInput());

        var changed = invoices.updateDraft(draft.id(), input(List.of(item("Nur eins", 2000, 5000, 0)),
                TotalDiscount.amount(1000)));

        assertThat(changed.items()).hasSize(1);
        assertThat(changed.netTotalCents()).isEqualTo(9_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_item").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_tax_total").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void draftRecordsItsCreatorAndStartsAtVersionOne() {
        var draft = invoices.createDraft(exampleInput());

        assertThat(draft.createdBy()).isEqualTo("system");
        assertThat(draft.version()).isEqualTo(1);
    }

    @Test
    void everySaveIncrementsTheVersion() {
        var draft = invoices.createDraft(exampleInput());

        var saved = invoices.updateDraft(draft.id(), exampleInput(), draft.version());

        assertThat(saved.version()).isEqualTo(2);
    }

    @Test
    void savingWithAnOldVersionIsRejectedAndChangesNothing() {
        var draft = invoices.createDraft(exampleInput());
        invoices.updateDraft(draft.id(), exampleInput(), draft.version());

        assertThatThrownBy(() -> invoices.updateDraft(draft.id(), input(List.of(item("Nur eins", 2000, 5000, 0)),
                TotalDiscount.none()), draft.version()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("invoice.error.conflict");
        assertThat(invoices.find(draft.id()).orElseThrow().items()).hasSize(draft.items().size());
    }

    @Test
    void draftCanBeDeletedWithItsItems() {
        var draft = invoices.createDraft(exampleInput());

        invoices.deleteDraft(draft.id());

        assertThat(invoices.find(draft.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_item").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_tax_total").query(Long.class).single()).isZero();
    }

    @Test
    void finalizedInvoiceCannotBeChangedOrDeleted() {
        var draft = invoices.createDraft(exampleInput());
        jdbc.sql("UPDATE document SET status = 'FINAL', number = 'RE202600001' WHERE id = :id")
                .param("id", draft.id())
                .update();

        assertThatThrownBy(() -> invoices.updateDraft(draft.id(), input(List.of(), TotalDiscount.none())))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("document.error.notDraft");
        assertThatThrownBy(() -> invoices.deleteDraft(draft.id()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("document.error.notDraft");

        var stored = invoices.find(draft.id()).orElseThrow();
        assertThat(stored.items()).hasSize(3);
        assertThat(stored.grossTotalCents()).isEqualTo(48_545);
        assertThat(stored.isDraft()).isFalse();
    }

    @Test
    void itemsAndTaxTotalsCannotBeAttachedToAFinalizedInvoice() {
        var draft = invoices.createDraft(exampleInput());
        jdbc.sql("UPDATE document SET status = 'FINAL', number = 'RE202600001' WHERE id = :id")
                .param("id", draft.id())
                .update();
        var item = draft.items().getFirst();

        assertThatThrownBy(() -> items.insert(draft.id(), item)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> documents.replaceTaxTotals(draft.id(), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invoices.find(draft.id()).orElseThrow().items()).hasSize(3);
    }

    @Test
    void hugePricesAreRejectedInsteadOfOverflowing() {
        var huge = new InvoiceItemInput("x", 999_999_999, "Std", 9_999_999_999L, 0, "E", 0, null);
        var tooHuge = new InvoiceItemInput("x", 1000, "Std", 10_000_000_000L, 0, "E", 0, null);

        assertThat(invoices.calculate(input(List.of(huge, huge, huge), TotalDiscount.none())).subtotalCents())
                .isPositive();
        assertRejected(input(List.of(tooHuge), TotalDiscount.none()), "validation.money");
    }

    @Test
    void invalidInputIsRejected() {
        var valid = List.of(item("Beratung", 1000, 10_000, 0));

        assertRejected(new InvoiceInput(null, TODAY, null, null, 14, null, null, null, null, valid),
                "invoice.error.customer");
        assertRejected(new InvoiceInput(customerId, null, null, null, 14, null, null, null, null, valid),
                "validation.required");
        assertRejected(new InvoiceInput(customerId, TODAY, TODAY, TODAY.minusDays(1), 14, null, null, null, null,
                valid), "invoice.error.servicePeriod");
        assertRejected(new InvoiceInput(customerId, TODAY, TODAY, null, 14, null, null, null, null, valid),
                "invoice.error.servicePeriod");
        assertRejected(new InvoiceInput(customerId, TODAY, null, null, 400, null, null, null, null, valid),
                "validation.paymentDays");
        assertRejected(input(valid, TotalDiscount.amount(10_001)), "invoice.error.discountTooHigh");
        assertRejected(input(valid, TotalDiscount.percent(10_001)), "validation.percent");
        assertRejected(input(List.of(item(" ", 1000, 100, 0)), TotalDiscount.none()), "validation.required");
        assertRejected(input(List.of(item("x", 0, 100, 0)), TotalDiscount.none()), "validation.quantity");
        assertRejected(input(List.of(item("x", 1000, -1, 0)), TotalDiscount.none()), "validation.money");
        assertRejected(input(List.of(item("x", 1000, 1, 10_001)), TotalDiscount.none()), "validation.percent");
        assertRejected(input(List.of(new InvoiceItemInput("x", 1000, "Xyz", 1, 0, "E", 0, null)),
                TotalDiscount.none()), "invoice.error.unit");
        assertRejected(input(List.of(new InvoiceItemInput("x", 1000, null, 1, 0, "Q", 0, null)),
                TotalDiscount.none()), "catalog.error.taxCategory");
    }

    @Test
    void violationsNameTheFieldAndTheRowOfTheItem() {
        var ok = item("Beratung", 1000, 10_000, 0);
        var cases = new Object[][] {
            {input(List.of(ok, item(" ", 1000, 100, 0)), TotalDiscount.none()), "validation.required",
                    "items[1].description"},
            {input(List.of(ok, item("x".repeat(2001), 1000, 100, 0)), TotalDiscount.none()), "validation.tooLong",
                    "items[1].description"},
            {input(List.of(ok, item("x", -1, 100, 0)), TotalDiscount.none()), "validation.quantity",
                    "items[1].quantityThousandths"},
            {input(List.of(ok, item("x", 1_000_000_000L, 100, 0)), TotalDiscount.none()), "validation.quantity",
                    "items[1].quantityThousandths"},
            {input(List.of(ok, item("x", 1000, 10_000_000_000L, 0)), TotalDiscount.none()), "validation.money",
                    "items[1].unitPriceCents"},
            {input(List.of(ok, item("x", 1000, 100, -1)), TotalDiscount.none()), "validation.percent",
                    "items[1].discountBp"},
            {input(List.of(ok, item("x", 1000, 100, 10_001)), TotalDiscount.none()), "validation.percent",
                    "items[1].discountBp"},
            {input(List.of(ok, new InvoiceItemInput("x", 1000, "Std", 1, 0, "E", 10_001, null)),
                    TotalDiscount.none()), "validation.percent", "items[1].taxRateBp"},
            {input(List.of(ok, new InvoiceItemInput("x", 1000, "Xyz", 1, 0, "E", 0, null)), TotalDiscount.none()),
                    "invoice.error.unit", "items[1].unitCode"},
            {input(List.of(ok, new InvoiceItemInput("x", 1000, "Std", 1, 0, "Q", 0, null)), TotalDiscount.none()),
                    "catalog.error.taxCategory", "items[1].taxCategory"},
            {input(List.of(ok, new InvoiceItemInput("x", 1000, "Std", 1, 0, "E", 500, null)), TotalDiscount.none()),
                    "invoice.error.taxRateNotAllowed", "items[1].taxRateBp"},
            {input(List.of(ok), TotalDiscount.percent(-1)), "validation.percent", "totalDiscount.valid"},
            {input(List.of(ok), TotalDiscount.amount(-1)), "validation.percent", "totalDiscount.valid"},
            {new InvoiceInput(customerId, TODAY, null, null, -1, null, null, null, null, List.of(ok)),
                    "validation.paymentDays", "paymentDays"},
            {new InvoiceInput(customerId, TODAY, null, null, 14, "x".repeat(301), null, null, null, List.of(ok)),
                    "validation.tooLong", "subject"},
            {new InvoiceInput(customerId, TODAY, null, null, 14, null, "x".repeat(4001), null, null, List.of(ok)),
                    "validation.tooLong", "headerText"},
            {new InvoiceInput(customerId, TODAY, null, null, 14, null, null, "x".repeat(4001), null, List.of(ok)),
                    "validation.tooLong", "footerText"},
            {new InvoiceInput(customerId, null, null, null, 14, null, null, null, null, List.of(ok)),
                    "validation.required", "issueDate"},
        };

        for (var testCase : cases) {
            assertThatThrownBy(() -> invoices.createDraft((InvoiceInput) testCase[0]))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessage((String) testCase[1])
                    .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo(testCase[2]));
        }
    }

    @Test
    void allViolationsOfTheItemsAreReported() {
        var input = input(List.of(item(" ", 1000, 100, 0), item("x", 0, 100, 0)), TotalDiscount.none());

        assertThatThrownBy(() -> invoices.createDraft(input))
                .satisfies(e -> assertThat(((RuleViolationException) e).violations()).extracting("field")
                        .containsExactly("items[0].description", "items[1].quantityThousandths"));
    }

    @Test
    void archivedCustomerIsNotSelectableForNewDrafts() {
        customers.archive(customerId);

        assertRejected(input(List.of(), TotalDiscount.none()), "invoice.error.customerArchived");
    }

    @Test
    void listShowsStatusAndCustomerAndFiltersByStatusAndText() {
        var draft = invoices.createDraft(exampleInput());
        var other = invoices.createDraft(exampleInput());
        jdbc.sql("UPDATE document SET status = 'FINAL', number = 'RE202600001' WHERE id = :id")
                .param("id", other.id())
                .update();

        assertThat(invoices.list("", null)).hasSize(2);
        assertThat(invoices.list("", DocumentStatus.DRAFT)).extracting(InvoiceSummary::id).containsExactly(draft.id());
        assertThat(invoices.list("MÜLLER", DocumentStatus.FINAL)).extracting(InvoiceSummary::number)
                .containsExactly("RE202600001");
        assertThat(invoices.list("schmidt", null)).isEmpty();
        assertThat(invoices.list("", DocumentStatus.DRAFT).getFirst().customerName()).isEqualTo("Müller GmbH");
    }

    private void assertRejected(InvoiceInput input, String messageKey) {
        assertThatThrownBy(() -> invoices.createDraft(input))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage(messageKey);
    }
}
