package de.cgoit.faktura.recurring;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.UnitInfo;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.document.CalculationItem;
import de.cgoit.faktura.document.InvoiceCalculator;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.recurring.internal.PlanRepository;
import de.cgoit.faktura.recurring.internal.PlanRepository.Row;
import de.cgoit.faktura.recurring.internal.RecurringProperties;
import de.cgoit.faktura.recurring.internal.RunRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains recurring plans and answers what waits for approval. A change to a plan only affects drafts generated
 * after the change; drafts and invoices that exist stay as they are.
 */
@Service
public class RecurringPlanService {

    /** The net amount shown in the list does not depend on tax, so a rate-free category is used for the calculation. */
    private static final String NET_ONLY_CATEGORY = "E";

    private final PlanRepository plans;
    private final RunRepository runs;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final UnitService units;
    private final InvoiceService invoices;
    private final TextTemplateService texts;
    private final InputValidator validator;
    private final RecurringProperties properties;
    private final Clock clock;

    public RecurringPlanService(PlanRepository plans, RunRepository runs, CustomerService customers,
            CatalogService catalog, UnitService units, InvoiceService invoices, TextTemplateService texts,
            InputValidator validator, RecurringProperties properties, Clock clock) {
        this.plans = plans;
        this.runs = runs;
        this.customers = customers;
        this.catalog = catalog;
        this.units = units;
        this.invoices = invoices;
        this.texts = texts;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a plan. The first period to bill is the first one that starts no earlier than the catch-up window
     * allows, so an anchor in the past does not bring in old periods.
     *
     * @throws RuleViolationException if the input is invalid
     */
    @Transactional
    public PlanInfo create(PlanInput rawInput) {
        var input = validated(rawInput);

        var id = UUID.randomUUID().toString();
        var index = startIndex(input);

        plans.insert(id, input, index, now());
        plans.replaceItems(id, input.items());

        return load(requireRow(id));
    }

    /**
     * Replaces the content of a plan. When anchor date or interval change, the next period is recomputed as the first
     * one that starts after the last generated period (or like for a new plan if there is none).
     *
     * @throws RuleViolationException if the plan is unknown or the input is invalid
     */
    @Transactional
    public PlanInfo update(String id, PlanInput rawInput) {
        return update(id, rawInput, requireRow(id).version());
    }

    /**
     * Replaces the content of a plan like {@link #update(String, PlanInput)} and increments its version.
     *
     * @param expectedVersion the version the caller read when it opened the plan
     * @throws RuleViolationException if the plan is unknown, the input is invalid, or someone saved the plan after the
     *         caller read it ({@code recurring.error.conflict})
     */
    @Transactional
    public PlanInfo update(String id, PlanInput rawInput, long expectedVersion) {
        var existing = requireRow(id);

        if (existing.status() == RecurringStatus.ENDED && rawInput.status() != RecurringStatus.ENDED) {
            throw new RuleViolationException("recurring.error.ended");
        }

        var input = validated(rawInput);

        var index = existing.nextPeriodIndex();

        if (!existing.anchorDate().equals(input.anchorDate()) || existing.interval() != input.interval()) {
            index = runs.lastServiceTo(id)
                    .map(last -> PeriodCalculator.firstIndexOnOrAfter(input.anchorDate(), input.interval(),
                            last.plusDays(1)))
                    .orElseGet(() -> startIndex(input));
        }

        if (!plans.update(id, input, index, now(), expectedVersion)) {
            throw new RuleViolationException("recurring.error.conflict");
        }

        plans.replaceItems(id, input.items());

        return load(requireRow(id));
    }

    /**
     * Pauses, reactivates or ends a plan.
     *
     * @throws RuleViolationException if the plan is unknown or already ended
     */
    @Transactional
    public void setStatus(String id, RecurringStatus status) {
        var existing = requireRow(id);

        if (existing.status() == RecurringStatus.ENDED && status != RecurringStatus.ENDED) {
            throw new RuleViolationException("recurring.error.ended");
        }

        plans.updateStatus(id, status, now());
    }

    /**
     * Finds a plan with its item templates.
     */
    @Transactional(readOnly = true)
    public Optional<PlanInfo> find(String id) {
        return plans.findById(id)
                .map(this::load);
    }

    /**
     * Lists all plans, active ones first.
     */
    @Transactional(readOnly = true)
    public List<PlanSummary> list() {
        var names = customers.search("", CustomerStatus.ALL)
                .stream()
                .collect(Collectors.toMap(CustomerInfo::id, CustomerInfo::name));

        return plans.findAll()
                .stream()
                .map(row -> summary(row, names.getOrDefault(row.customerId(), "")))
                .sorted(Comparator.comparing(PlanSummary::status))
                .toList();
    }

    /**
     * Returns the periods a plan with these settings would generate next, for the preview in the form.
     *
     * @param count how many periods to return
     */
    public List<Period> preview(RecurringInterval interval, LocalDate anchorDate, LocalDate endDate, int count) {
        var first = PeriodCalculator.firstIndexOnOrAfter(anchorDate, interval,
                LocalDate.now(clock).minusDays(properties.catchUpDays()));

        return PeriodCalculator.upcoming(anchorDate, interval, endDate, first, count);
    }

    /**
     * Lists the generated drafts that wait for the user's decision, oldest period first.
     */
    @Transactional(readOnly = true)
    public List<ReleaseEntry> pendingReleases() {
        var result = new ArrayList<ReleaseEntry>();

        for (var run : runs.findByStatus(RunStatus.DRAFT)) {
            var plan = plans.findById(run.planId());
            var invoice = run.documentId() == null ? Optional.<InvoiceInfo>empty()
                    : invoices.find(run.documentId());

            if (plan.isEmpty() || invoice.isEmpty()) {
                continue;
            }

            var customerName = customers.find(plan.get().customerId())
                    .map(CustomerInfo::name)
                    .orElse("");

            result.add(new ReleaseEntry(run.id(), run.planId(), plan.get().name(), customerName, run.serviceFrom(),
                    run.serviceTo(), run.documentId(), invoice.get().grossTotalCents(),
                    LocalDate.parse(run.createdAt().substring(0, 10)), plan.get().interval(),
                    plan.get().anchorDate()));
        }

        return result;
    }

    /**
     * Counts the drafts waiting for approval, for the badge in the navigation.
     */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return runs.countPending();
    }

    /**
     * Skips a period: the draft is deleted and the run is kept as a record, so the period is not generated again.
     * Only a draft is ever deleted; an invoice that was finalized in the meantime is left alone.
     *
     * @throws RuleViolationException if the run is unknown or no longer waits for approval
     */
    @Transactional
    public void skip(String runId) {
        var run = runs.findById(runId)
                .orElseThrow(() -> new RuleViolationException("recurring.error.unknownRun"));

        if (run.status() != RunStatus.DRAFT) {
            throw new RuleViolationException("recurring.error.notPending");
        }

        if (run.documentId() == null || invoices.find(run.documentId()).isEmpty()) {
            runs.markSkipped(runId);

            return;
        }

        invoices.deleteDraft(run.documentId());
    }

    /**
     * Returns the day the daily job treats as today.
     */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private int startIndex(PlanInput input) {
        return PeriodCalculator.firstIndexOnOrAfter(input.anchorDate(), input.interval(),
                today().minusDays(properties.catchUpDays()));
    }

    private PlanSummary summary(Row row, String customerName) {
        var items = plans.findItems(row.id());
        var calculationItems = items.stream()
                .map(item -> new CalculationItem(item.quantityThousandths(), item.unitPriceCents(),
                        item.discountBp(), NET_ONLY_CATEGORY, 0))
                .toList();
        var discount = row.totalDiscount() == null ? TotalDiscount.none() : row.totalDiscount();
        long net;

        try {
            net = InvoiceCalculator.calculate(calculationItems, discount)
                    .netCents();
        } catch (ArithmeticException e) {
            net = 0;
        }

        var next = PeriodCalculator.period(row.anchorDate(), row.interval(), row.nextPeriodIndex());

        if (row.endDate() != null && next.from().isAfter(row.endDate())) {
            next = null;
        }

        return new PlanSummary(row.id(), row.name(), row.customerId(), customerName, row.interval(), row.status(),
                row.status() == RecurringStatus.ACTIVE ? next : null, net, row.lastError());
    }

    private Row requireRow(String id) {
        return plans.findById(id)
                .orElseThrow(() -> new RuleViolationException("recurring.error.unknown"));
    }

    private PlanInfo load(Row row) {
        return plans.findInfo(row.id())
                .orElseThrow();
    }

    private String now() {
        return Instant.now(clock)
                .toString();
    }

    /**
     * Normalizes the input and checks it: the single-value rules as {@link InputValidator} reads them from the
     * annotations, then the rules that need other data. Returns the normalized input to store.
     */
    private PlanInput validated(PlanInput raw) {
        var input = normalized(raw);

        validator.check(input);

        if (customers.find(input.customerId()).isEmpty()) {
            throw new RuleViolationException("invoice.error.customer", "customerId");
        }

        if (input.endDate() != null && input.endDate().isBefore(input.anchorDate())) {
            throw new RuleViolationException("recurring.error.endBeforeAnchor", "endDate");
        }

        if (input.contactId() != null && !input.contactId().isBlank()) {
            var belongs = customers.findContact(input.contactId())
                    .filter(contact -> contact.customerId().equals(input.customerId()))
                    .isPresent();

            if (!belongs) {
                throw new RuleViolationException("invoice.error.contact", "contactId");
            }
        }

        texts.validate(input.headerText());
        texts.validate(input.footerText());

        validateItems(input.items());

        return input;
    }

    /**
     * Strips the name and turns blank texts, units and categories into {@code null}, item by item and in the same
     * order, so that the index of a violation is the index of the row in the form.
     */
    private static PlanInput normalized(PlanInput input) {
        var normalizedItems = input.items() == null
                ? null
                : input.items().stream()
                        .map(RecurringPlanService::normalized)
                        .toList();

        return new PlanInput(input.customerId(), input.name() == null ? null : input.name().strip(),
                input.interval(), input.anchorDate(), input.endDate(), input.leadDays(), input.status(),
                input.paymentDays(), blankToNull(input.subject()), blankToNull(input.headerText()),
                blankToNull(input.footerText()), input.totalDiscount(), blankToNull(input.contactId()),
                normalizedItems, blankToNull(input.customerReference()));
    }

    private static PlanItemInput normalized(PlanItemInput item) {
        if (item == null) {
            return null;
        }

        return new PlanItemInput(item.description(), item.quantityThousandths(), blankToNull(item.unitCode()),
                item.unitPriceCents(), item.discountBp(), blankToNull(item.taxCategory()), item.catalogItemId());
    }

    private void validateItems(List<PlanItemInput> items) {
        var unitCodes = units.list()
                .stream()
                .map(UnitInfo::code)
                .toList();
        var categories = catalog.taxCategories();

        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var path = "items[" + index + "].";

            if (item.taxCategory() != null && !categories.contains(item.taxCategory())) {
                throw new RuleViolationException("catalog.error.taxCategory", path + "taxCategory");
            }

            if (item.unitCode() != null && !unitCodes.contains(item.unitCode())) {
                throw new RuleViolationException("invoice.error.unit", path + "unitCode");
            }
        }
    }
}
