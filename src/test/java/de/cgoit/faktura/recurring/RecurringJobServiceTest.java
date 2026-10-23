package de.cgoit.faktura.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.recurring.internal.PeriodProcessor;
import de.cgoit.faktura.recurring.internal.PlanChangedException;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class RecurringJobServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private RecurringPlanService plans;

    @Autowired
    private RecurringJobService job;

    @Autowired
    private PeriodProcessor processor;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    private String customerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E')
                """).update();
        customerId = customer("Müller GmbH");
    }

    @AfterEach
    void cleanUp() {
        TestArchive.clear();
        jdbc.sql("DELETE FROM recurring_run").update();
        jdbc.sql("DELETE FROM recurring_plan_item").update();
        jdbc.sql("DELETE FROM recurring_plan").update();
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
    }

    private String customer(String name) {
        return customers.create(new CustomerInput(name, null, null, null, "DE", null, null, null, null, null))
                .id();
    }

    private PlanInput input(String anchor, RecurringInterval interval, int leadDays) {
        var item = new PlanItemInput("Wartung und Hosting", 1000, "Std", 45_000, 0, null, null);

        return new PlanInput(customerId, "Wartungsvertrag", interval, LocalDate.parse(anchor), null, leadDays,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, List.of(item));
    }

    /**
     * Creates a plan and sets its first period explicitly, independent of the real clock.
     */
    private PlanInfo plan(PlanInput input) {
        var created = plans.create(input);

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0 WHERE id = :id")
                .param("id", created.id())
                .update();

        return plans.find(created.id())
                .orElseThrow();
    }

    private long count(String sql) {
        return jdbc.sql("SELECT COUNT(*) FROM " + sql)
                .query(Long.class)
                .single();
    }

    @Test
    void savingAPlanWithAnOldVersionIsRejected() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));
        var saved = plans.update(plan.id(), input("2026-11-01", RecurringInterval.QUARTER, 14), plan.version());

        assertThat(saved.version()).isEqualTo(plan.version() + 1);
        assertThatThrownBy(() -> plans.update(plan.id(), input("2026-11-01", RecurringInterval.QUARTER, 7),
                plan.version()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("recurring.error.conflict");
    }

    @Test
    void theJobDoesNotChangeTheVersionOfAPlan() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        job.runDue(TODAY);

        assertThat(plans.find(plan.id()).orElseThrow().version()).isEqualTo(plan.version());
    }

    @Test
    void createsADraftForAPeriodStartingWithinTheLeadTime() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        assertThat(job.runDue(TODAY)).isEqualTo(1);

        var run = jdbc.sql("SELECT status, document_id, service_from, service_to FROM recurring_run")
                .query()
                .singleRow();
        var draft = invoices.find((String) run.get("document_id"))
                .orElseThrow();

        assertThat(run.get("status")).isEqualTo("DRAFT");
        assertThat(run.get("service_from")).isEqualTo("2026-11-01");
        assertThat(run.get("service_to")).isEqualTo("2027-01-31");
        assertThat(draft.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(draft.number()).isNull();
        assertThat(draft.customerId()).isEqualTo(customerId);
        assertThat(draft.issueDate()).isEqualTo(TODAY);
        assertThat(draft.serviceFrom()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(draft.serviceTo()).isEqualTo(LocalDate.parse("2027-01-31"));
        assertThat(draft.subject()).isEqualTo("Wartungsvertrag, 01.11.2026 bis 31.01.2027");
        assertThat(draft.items()).hasSize(1);
        assertThat(draft.items().getFirst().description()).isEqualTo("Wartung und Hosting");
        assertThat(draft.items().getFirst().unitPriceCents()).isEqualTo(45_000);
        assertThat(draft.items().getFirst().taxCategory()).isEqualTo("E");
        assertThat(draft.items().getFirst().taxRateBp()).isZero();
        assertThat(draft.grossTotalCents()).isEqualTo(45_000);
        assertThat(plans.find(plan.id()).orElseThrow().nextPeriodIndex()).isEqualTo(1);
        assertThat(plans.pendingCount()).isEqualTo(1);
    }

    @Test
    void takesCategoryAndRateFromTheTaxProfileOfThePeriod() {
        jdbc.sql("UPDATE tax_profile SET valid_to = '2026-12-31'").update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES ('p2', 'c1', '2027-01-01', NULL, 'REGULAR', 1900, 'S')
                """).update();
        plan(input("2027-01-01", RecurringInterval.MONTH, 14));

        assertThat(job.runDue(LocalDate.parse("2026-12-20"))).isEqualTo(1);

        var draft = invoices.find(jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single())
                .orElseThrow();

        assertThat(draft.items().getFirst().taxCategory()).isEqualTo("S");
        assertThat(draft.items().getFirst().taxRateBp()).isEqualTo(1900);
        assertThat(draft.grossTotalCents()).isEqualTo(53_550);
    }

    @Test
    void theDraftCarriesTheDefaultCustomerReferenceOfThePlan() {
        var base = input("2026-11-01", RecurringInterval.QUARTER, 14);

        plan(new PlanInput(base.customerId(), base.name(), base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, base.items(), "  Vertrag 12 "));

        assertThat(job.runDue(TODAY)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT customer_reference FROM document").query(String.class).single())
                .isEqualTo("Vertrag 12");
        assertThat(jdbc.sql("SELECT shipping_cents FROM document").query(Long.class).single()).isZero();
    }

    @Test
    void runningTwiceCreatesNoSecondDraft() {
        plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        assertThat(job.runDue(TODAY)).isEqualTo(1);
        assertThat(job.runDue(TODAY)).isZero();
        assertThat(job.runDue(TODAY.plusDays(3))).isZero();
        assertThat(count("recurring_run")).isEqualTo(1);
        assertThat(count("document WHERE status = 'DRAFT'")).isEqualTo(1);
    }

    @Test
    void anExistingRunForThePeriodPreventsANewDraft() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        jdbc.sql("""
                INSERT INTO recurring_run (id, plan_id, service_from, service_to, status, document_id, created_at)
                VALUES ('r1', :planId, '2026-11-01', '2027-01-31', 'SKIPPED', NULL, '2026-10-19T05:00:00Z')
                """)
                .param("planId", plan.id())
                .update();

        assertThat(job.runDue(TODAY)).isZero();
        assertThat(count("document")).isZero();
        assertThat(plans.find(plan.id()).orElseThrow().nextPeriodIndex()).isEqualTo(1);
    }

    @Test
    void doesNotGenerateBeforeTheLeadTimeBegins() {
        var plan = plan(input("2026-11-10", RecurringInterval.MONTH, 5));

        assertThat(job.runDue(TODAY)).isZero();
        assertThat(count("recurring_run")).isZero();
        assertThat(job.runDue(LocalDate.parse("2026-11-05"))).isEqualTo(1);
        assertThat(plans.find(plan.id()).orElseThrow().nextPeriodIndex()).isEqualTo(1);
    }

    @Test
    void leadTimeComesFromThePlan() {
        plan(input("2026-11-10", RecurringInterval.MONTH, 21));

        assertThat(job.runDue(TODAY)).isEqualTo(1);
    }

    @Test
    void pausedAndEndedPlansGenerateNothing() {
        var paused = plan(input("2026-11-01", RecurringInterval.MONTH, 30));
        var ended = plan(input("2026-11-01", RecurringInterval.MONTH, 30));

        plans.setStatus(paused.id(), RecurringStatus.PAUSED);
        plans.setStatus(ended.id(), RecurringStatus.ENDED);

        assertThat(job.runDue(TODAY)).isZero();
        assertThatThrownBy(() -> plans.setStatus(ended.id(), RecurringStatus.ACTIVE))
                .isInstanceOf(RuleViolationException.class);

        plans.setStatus(paused.id(), RecurringStatus.ACTIVE);

        assertThat(job.runDue(TODAY)).isEqualTo(1);
    }

    @Test
    void stopsAfterTheEndDateAndMarksThePlanEnded() {
        var created = plans.create(new PlanInput(customerId, "Befristet", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-15"), 60, RecurringStatus.ACTIVE, null, null,
                null, null, null, null, List.of(new PlanItemInput("Pflege", 1000, null, 10_000, 0, null, null))));

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0").update();

        assertThat(job.runDue(TODAY)).isEqualTo(2);
        assertThat(job.runDue(TODAY)).isZero();
        assertThat(plans.find(created.id()).orElseThrow().status()).isEqualTo(RecurringStatus.ENDED);
        assertThat(jdbc.sql("SELECT service_from FROM recurring_run ORDER BY service_from")
                .query(String.class)
                .list()).containsExactly("2026-11-01", "2026-12-01");
    }

    @Test
    void catchesUpRecentPeriodsAndRecordsOlderOnesAsSkipped() {
        plan(input("2026-07-20", RecurringInterval.MONTH, 14));

        assertThat(job.runDue(TODAY)).isEqualTo(2);

        var rows = jdbc.sql("SELECT service_from, status, document_id FROM recurring_run ORDER BY service_from")
                .query()
                .listOfRows();

        assertThat(rows).extracting(row -> row.get("service_from") + " " + row.get("status"))
                .containsExactly("2026-07-20 SKIPPED", "2026-08-20 SKIPPED", "2026-09-20 DRAFT",
                        "2026-10-20 DRAFT");
        assertThat(rows.get(0).get("document_id")).isNull();
        assertThat(rows.get(1).get("document_id")).isNull();
        assertThat(job.runDue(TODAY)).isZero();
        assertThat(count("document")).isEqualTo(2);
    }

    @Test
    void aFailingPlanKeepsItsStateAndDoesNotBlockOthers() {
        var other = customer("Meier AG");
        var failing = plan(input("2026-11-01", RecurringInterval.MONTH, 14));
        var fine = plan(new PlanInput(other, "Support", RecurringInterval.MONTH, LocalDate.parse("2026-11-01"), null,
                14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Support", 1000, null, 5_000, 0, null, null))));

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0").update();
        customers.archive(customerId);

        assertThat(job.runDue(TODAY)).isEqualTo(1);

        var failed = plans.find(failing.id()).orElseThrow();

        assertThat(failed.nextPeriodIndex()).isZero();
        assertThat(failed.lastError()).isEqualTo("invoice.error.customerArchived");
        assertThat(count("recurring_run WHERE plan_id = '" + failing.id() + "'")).isZero();
        assertThat(plans.find(fine.id()).orElseThrow().nextPeriodIndex()).isEqualTo(1);

        customers.restore(customerId);

        assertThat(job.runDue(TODAY)).isEqualTo(1);
        assertThat(plans.find(failing.id()).orElseThrow().lastError()).isNull();
    }

    @Test
    void withoutATaxProfileNothingIsStored() {
        var plan = plan(input("2026-11-01", RecurringInterval.MONTH, 14));

        jdbc.sql("DELETE FROM tax_profile").update();

        assertThat(job.runDue(TODAY)).isZero();
        assertThat(count("document")).isZero();
        assertThat(count("recurring_run")).isZero();
        assertThat(plans.find(plan.id()).orElseThrow().lastError()).isEqualTo("invoice.error.noTaxProfile");
    }

    @Test
    void aFailureAfterTheDraftWasCreatedRollsBackTheDraft() {
        plan(input("2026-11-01", RecurringInterval.MONTH, 14));
        jdbc.sql("""
                CREATE TRIGGER fail_run BEFORE INSERT ON recurring_run
                BEGIN SELECT RAISE(ABORT, 'boom'); END
                """).update();

        try {
            assertThat(job.runDue(TODAY)).isZero();
            assertThat(count("document")).isZero();
            assertThat(count("document_item")).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER fail_run").update();
        }

        assertThat(job.runDue(TODAY)).isEqualTo(1);
        assertThat(count("document")).isEqualTo(1);
    }

    @Test
    void finalizingTheDraftReleasesTheRun() {
        plan(input("2026-11-01", RecurringInterval.QUARTER, 14));
        job.runDue(TODAY);

        var documentId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();
        var finalized = finalizer.finalizeInvoice(documentId);

        assertThat(finalized.status()).isEqualTo(DocumentStatus.FINAL);
        assertThat(finalized.number()).isNotNull();
        assertThat(jdbc.sql("SELECT status FROM recurring_run")
                .query(String.class)
                .single()).isEqualTo("RELEASED");
        assertThat(plans.pendingCount()).isZero();
        assertThatThrownBy(() -> plans.skip(jdbc.sql("SELECT id FROM recurring_run")
                .query(String.class)
                .single()))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("recurring.error.notPending");
        assertThat(invoices.find(documentId)).isPresent();
    }

    @Test
    void aDraftForAZugferdCustomerRunsThroughTheSameFinalizePipeline() {
        jdbc.sql("UPDATE company SET owner = 'Erika Beispiel', street = 'Musterweg 1', zip = '12345', "
                + "city = 'Beispielstadt', tax_number = '12/345/67890', iban = 'DE02120300000000202051', "
                + "bic = 'BYLADEM1001', bank_name = 'Testbank'").update();

        var einvoiceCustomer = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321",
                "Musterhausen", "DE", null, null, null, null, null, EInvoiceMode.ZUGFERD)).id();

        plan(new PlanInput(einvoiceCustomer, "Wartung", RecurringInterval.MONTH, LocalDate.parse("2026-11-01"), null,
                14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null))));
        job.runDue(TODAY);

        var documentId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();

        finalizer.finalizeInvoice(documentId);

        assertThat(count("file WHERE type = 'XML'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM recurring_run")
                .query(String.class)
                .single()).isEqualTo("RELEASED");
    }

    @Test
    void deletingTheDraftInTheEditorSkipsTheRun() {
        plan(input("2026-11-01", RecurringInterval.QUARTER, 14));
        job.runDue(TODAY);

        var documentId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();

        invoices.deleteDraft(documentId);

        var run = jdbc.sql("SELECT status, document_id FROM recurring_run")
                .query()
                .singleRow();

        assertThat(run.get("status")).isEqualTo("SKIPPED");
        assertThat(run.get("document_id")).isNull();
        assertThat(job.runDue(TODAY)).isZero();
        assertThat(count("document")).isZero();
    }

    @Test
    void skippingDeletesTheDraftAndKeepsTheRun() {
        plan(input("2026-11-01", RecurringInterval.QUARTER, 14));
        job.runDue(TODAY);

        var entry = plans.pendingReleases()
                .getFirst();

        assertThat(entry.customerName()).isEqualTo("Müller GmbH");
        assertThat(entry.grossCents()).isEqualTo(45_000);

        plans.skip(entry.runId());

        assertThat(count("document")).isZero();
        assertThat(jdbc.sql("SELECT status FROM recurring_run")
                .query(String.class)
                .single()).isEqualTo("SKIPPED");
        assertThat(plans.pendingReleases()).isEmpty();
    }

    @Test
    void changingAPlanDoesNotTouchExistingDrafts() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        job.runDue(TODAY);

        var documentId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();
        var changed = new PlanInput(customerId, "Neuer Name", RecurringInterval.QUARTER, LocalDate.parse("2026-11-01"),
                null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Teurer", 1000, null, 99_900, 0, null, null)));

        plans.update(plan.id(), changed);

        var draft = invoices.find(documentId)
                .orElseThrow();

        assertThat(draft.subject()).startsWith("Wartungsvertrag");
        assertThat(draft.items().getFirst().unitPriceCents()).isEqualTo(45_000);
        assertThat(plans.find(plan.id()).orElseThrow().nextPeriodIndex()).isEqualTo(1);
    }

    @Test
    void aNewPlanStartsAtThePeriodInsideTheCatchUpWindow() {
        var created = plans.create(input("2020-01-15", RecurringInterval.QUARTER, 14));
        var next = created.nextPeriod();

        assertThat(next.from()).isAfterOrEqualTo(plans.today().minusDays(31));
        assertThat(PeriodCalculator.period(created.anchorDate(), created.interval(), created.nextPeriodIndex() - 1)
                .from()).isBefore(plans.today().minusDays(31));
    }

    @Test
    void changingTheAnchorMovesTheNextPeriodBehindTheLastRun() {
        var plan = plan(input("2026-11-01", RecurringInterval.QUARTER, 14));

        job.runDue(TODAY);
        plans.update(plan.id(), input("2026-11-15", RecurringInterval.MONTH, 14));

        // last run ended 2027-01-31, so the next period starts on or after 2027-02-01 on the new anchor
        var updated = plans.find(plan.id()).orElseThrow();

        assertThat(updated.nextPeriod().from()).isEqualTo(LocalDate.parse("2027-02-15"));
    }

    @Test
    void anEndedPlanCannotBeReactivatedByEditing() {
        var ended = plan(input("2026-11-01", RecurringInterval.MONTH, 14));

        plans.setStatus(ended.id(), RecurringStatus.ENDED);

        assertThatThrownBy(() -> plans.update(ended.id(), input("2026-11-01", RecurringInterval.MONTH, 14)))
                .hasMessage("recurring.error.ended");
    }

    @Test
    void aPlanEditedDuringTheRunIsNotAdvancedWithStaleSettings() {
        var stale = plan(input("2026-11-01", RecurringInterval.MONTH, 14));

        plans.update(stale.id(), input("2026-11-15", RecurringInterval.QUARTER, 14));

        assertThatThrownBy(() -> processor.process(stale, PeriodCalculator.period(stale.anchorDate(),
                stale.interval(), 0), TODAY, true))
                .isInstanceOf(PlanChangedException.class);
        assertThat(count("document")).isZero();
        assertThat(count("recurring_run")).isZero();
    }

    @Test
    void violationsNameTheFieldAndTheRowOfThePlan() {
        var ok = new PlanItemInput("A", 1000, null, 100, 0, null, null);
        var cases = new Object[][] {
            {new PlanItemInput(" ", 1000, null, 100, 0, null, null), "validation.required",
                    "items[1].description"},
            {new PlanItemInput("x".repeat(2001), 1000, null, 100, 0, null, null), "validation.tooLong",
                    "items[1].description"},
            {new PlanItemInput("x", 0, null, 100, 0, null, null), "validation.quantity",
                    "items[1].quantityThousandths"},
            {new PlanItemInput("x", 1_000_000_000L, null, 100, 0, null, null), "validation.quantity",
                    "items[1].quantityThousandths"},
            {new PlanItemInput("x", 1000, null, -1, 0, null, null), "validation.money",
                    "items[1].unitPriceCents"},
            {new PlanItemInput("x", 1000, null, 10_000_000_000L, 0, null, null), "validation.money",
                    "items[1].unitPriceCents"},
            {new PlanItemInput("x", 1000, null, 1, -1, null, null), "validation.percent", "items[1].discountBp"},
            {new PlanItemInput("x", 1000, null, 1, 10_001, null, null), "validation.percent",
                    "items[1].discountBp"},
            {new PlanItemInput("x", 1000, "Xyz", 1, 0, null, null), "invoice.error.unit", "items[1].unitCode"},
            {new PlanItemInput("x", 1000, null, 1, 0, "Q", null), "catalog.error.taxCategory",
                    "items[1].taxCategory"},
        };

        for (var testCase : cases) {
            var base = input("2026-11-01", RecurringInterval.MONTH, 14);
            var plan = withItems(base, List.of(ok, (PlanItemInput) testCase[0]));

            assertThatThrownBy(() -> plans.create(plan))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessage((String) testCase[1])
                    .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo(testCase[2]));
        }
    }

    @Test
    void violationsNameTheFieldOfThePlanValues() {
        var items = List.of(new PlanItemInput("A", 1000, null, 100, 0, null, null));
        var base = input("2026-11-01", RecurringInterval.MONTH, 14);

        assertViolation(new PlanInput(customerId, "x".repeat(201), base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, items), "validation.tooLong", "name");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 366,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, items), "recurring.error.leadDays",
                "leadDays");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, 366, null, null, null, null, null, items), "validation.paymentDays",
                "paymentDays");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, "x".repeat(301), null, null, null, null, items), "validation.tooLong",
                "subject");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, "x".repeat(4001), null, null, null, items),
                "validation.tooLong", "headerText");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, "x".repeat(4001), null, null, items),
                "validation.tooLong", "footerText");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, TotalDiscount.percent(10_001), null, items),
                "validation.percent", "totalDiscount.valid");
        assertViolation(new PlanInput(customerId, "A", base.interval(), null, null, 14, RecurringStatus.ACTIVE, null,
                null, null, null, null, null, items), "validation.required", "anchorDate");
        assertViolation(new PlanInput(customerId, "A", base.interval(), base.anchorDate(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, List.of()), "invoice.error.noItems",
                "items");
    }

    private static PlanInput withItems(PlanInput base, List<PlanItemInput> items) {
        return new PlanInput(base.customerId(), base.name(), base.interval(), base.anchorDate(), base.endDate(),
                base.leadDays(), base.status(), base.paymentDays(), base.subject(), base.headerText(),
                base.footerText(), base.totalDiscount(), base.contactId(), items);
    }

    private void assertViolation(PlanInput input, String key, String field) {
        assertThatThrownBy(() -> plans.create(input))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage(key)
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo(field));
    }

    @Test
    void planWithAnInvalidItemWritesItsMessageKeyAndCreatesNoDraftTwice() {
        var plan = plan(input("2026-11-01", RecurringInterval.MONTH, 14));

        jdbc.sql("UPDATE recurring_plan_item SET description = :text WHERE plan_id = :id")
                .param("text", " ")
                .param("id", plan.id())
                .update();
        job.runDue(TODAY);
        job.runDue(TODAY);

        assertThat(plans.find(plan.id()).orElseThrow().lastError()).isEqualTo("validation.required");
        assertThat(count("document")).isZero();
        assertThat(count("recurring_run")).isZero();
    }

    @Test
    void rejectsInvalidPlans() {
        assertThatThrownBy(() -> plans.create(new PlanInput(customerId, " ", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("A", 1000, null, 100, 0, null, null)))))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> plans.create(new PlanInput(customerId, "A", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-01"), 14, RecurringStatus.ACTIVE, null, null,
                null, null, null, null, List.of(new PlanItemInput("A", 1000, null, 100, 0, null, null)))))
                .hasMessage("recurring.error.endBeforeAnchor");
        assertThatThrownBy(() -> plans.create(new PlanInput(customerId, "A", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of())))
                .hasMessage("invoice.error.noItems");
        assertThatThrownBy(() -> plans.create(new PlanInput("unknown", "A", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("A", 1000, null, 100, 0, null, null)))))
                .hasMessage("invoice.error.customer");
    }
}
