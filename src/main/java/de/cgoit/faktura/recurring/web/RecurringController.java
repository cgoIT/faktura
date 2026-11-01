package de.cgoit.faktura.recurring.web;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.catalog.CatalogOrder;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.CatalogChoices;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.recurring.PlanInfo;
import de.cgoit.faktura.recurring.RecurringInterval;
import de.cgoit.faktura.recurring.RecurringJobService;
import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.recurring.RecurringStatus;
import de.cgoit.faktura.recurring.internal.RecurringProperties;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.FormErrors;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The page "Wiedervorlagen": drafts waiting for approval, the list of plans and the plan editor. Approving means
 * opening the draft in the normal invoice editor, whose review dialog and finalize pipeline are used unchanged.
 */
@Controller
@RequestMapping("/recurring")
public class RecurringController {

    private static final String LIST_VIEW = "recurring/list";
    private static final String FORM_VIEW = "recurring/form";
    private static final String FORM_ERROR = "invoice.error.form";
    private static final String FORM_ATTRIBUTE = "planForm";
    private static final int PREVIEW_COUNT = 6;
    private static final int CATALOG_RESULTS = 300;
    private static final String CATALOG_BASE = "/recurring";

    private final RecurringPlanService plans;
    private final RecurringJobService job;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final UnitService units;
    private final TextTemplateService texts;
    private final InvoiceService invoices;
    private final RecurringProperties properties;

    public RecurringController(RecurringPlanService plans, RecurringJobService job, CustomerService customers,
            CatalogService catalog, UnitService units, TextTemplateService texts, InvoiceService invoices,
            RecurringProperties properties) {
        this.plans = plans;
        this.job = job;
        this.customers = customers;
        this.catalog = catalog;
        this.units = units;
        this.texts = texts;
        this.invoices = invoices;
        this.properties = properties;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("releases", plans.pendingReleases());
        model.addAttribute("plans", plans.list());

        return LIST_VIEW;
    }

    /**
     * Runs the job for today on request. It only creates drafts; the same job runs daily by itself.
     */
    @PostMapping("/run")
    public String run(RedirectAttributes redirect) {
        try {
            var created = job.runDue(plans.today());

            redirect.addFlashAttribute("message", created > 0 ? "recurring.run.created" : "recurring.run.none");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", "recurring.error.unexpected");
        }

        return "redirect:/recurring";
    }

    @PostMapping("/runs/{runId}/skip")
    public String skip(@PathVariable String runId, RedirectAttributes redirect) {
        try {
            plans.skip(runId);
            redirect.addFlashAttribute("message", "recurring.skipped");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/recurring";
    }

    /**
     * Opens the plan editor empty, or prefilled from an invoice (customer, contact, items, discount); the first
     * period starts the day after the invoice's service period.
     */
    @GetMapping("/new")
    public String newPlan(@RequestParam(required = false) String fromInvoice, Model model) {
        var form = PlanForm.empty(properties.defaultLeadDays(), plans.today(),
                texts.defaultBody(TextScope.INVOICE, TextKind.HEADER)
                        .orElse(null),
                texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER)
                        .orElse(null));

        if (fromInvoice != null) {
            var invoice = invoices.find(fromInvoice)
                    .filter(found -> found.type() == DocumentType.INVOICE && !found.isDraft() && !found.isCancelled())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            form = form.fromInvoice(invoice);
        }

        return form(model, form, null);
    }

    @GetMapping("/{id}")
    public String show(@PathVariable String id, Model model) {
        var plan = find(id);

        return form(model, PlanForm.from(plan), plan);
    }

    @PostMapping
    public String create(PlanForm form, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, form, result, model, redirect);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, PlanForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        return save(find(id), form, result, model, redirect);
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable String id, @RequestParam RecurringStatus status,
            RedirectAttributes redirect) {
        find(id);

        try {
            plans.setStatus(id, status);
            redirect.addFlashAttribute("message", "recurring.statusChanged");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/recurring";
    }

    /**
     * Returns the next periods for the settings in the form, so the user sees what the plan will bill.
     */
    @PostMapping("/preview")
    public String preview(@RequestParam(required = false) RecurringInterval interval,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Model model) {
        model.addAttribute("periods", interval == null || anchorDate == null
                ? List.of()
                : plans.preview(interval, anchorDate, endDate, PREVIEW_COUNT));

        return "recurring/fragments :: preview";
    }

    @PostMapping("/items/add")
    public String addFreeTextItem(PlanForm form, BindingResult result, Model model) {
        return addItem(form, null, model);
    }

    @PostMapping("/items/add-catalog")
    public String addCatalogItem(PlanForm form, BindingResult result, @RequestParam String catalogItemId,
            Model model) {
        return addItem(form, catalogItemId, model);
    }

    @PostMapping("/items/remove")
    public String removeItem(PlanForm form, BindingResult result, @RequestParam int index, Model model) {
        var items = form.itemList();

        if (index >= 0 && index < items.size()) {
            items.remove(index);
        }

        itemsModel(model, form.withItems(items));

        return "recurring/fragments :: items";
    }

    /**
     * Appends a full copy of the item at {@code index} to the end of the list.
     */
    @PostMapping("/items/copy")
    public String copyItem(PlanForm form, BindingResult result, @RequestParam int index, Model model) {
        var items = form.itemList();

        if (index >= 0 && index < items.size()) {
            items.add(items.get(index));
        }

        itemsModel(model, form.withItems(items));

        return "recurring/fragments :: items";
    }

    /**
     * Searches the catalog for the picker of the plan form. Plans take every active entry; the tax profile is
     * checked when the draft is created.
     */
    @PostMapping("/catalog/search")
    public String searchCatalog(PlanForm form, BindingResult result,
            @RequestParam(defaultValue = "") String catalogQuery, Model model) {
        model.addAttribute("catalogChoices", catalogChoices(catalogQuery));
        model.addAttribute("catalogBase", CATALOG_BASE);

        return "fragments/catalog-results :: results(oob=false)";
    }

    private CatalogChoices catalogChoices(String query) {
        var found = catalog.search(query, true, CatalogOrder.NAME);

        return new CatalogChoices(found.stream().limit(CATALOG_RESULTS).toList(), found.size(), 0);
    }

    private String addItem(PlanForm form, String catalogItemId, Model model) {
        var items = form.itemList();

        if (catalogItemId == null) {
            items.add(PlanItemForm.empty());
        } else {
            catalog.find(catalogItemId)
                    .ifPresent(entry -> items.add(new PlanItemForm(
                            entry.description() == null ? entry.name() : entry.name() + "\n" + entry.description(),
                            "1", entry.unitCode(),
                            DecimalInput.formatCents(entry.unitPriceCents()), "0",
                            entry.taxCategory(), entry.id())));
        }

        itemsModel(model, form.withItems(items));

        return "recurring/fragments :: items";
    }

    private String save(PlanInfo existing, PlanForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        try {
            if (result.hasErrors()) {
                throw new RuleViolationException(FORM_ERROR);
            }

            var input = form.toInput();

            if (existing == null) {
                plans.create(input);
            } else {
                plans.update(existing.id(), input, form.requireVersion());
            }
        } catch (RuleViolationException e) {
            model.addAttribute("error", FormErrors.reject(e, result, PlanFields::formField, FORM_ERROR));

            return form(model, form, existing);
        }

        redirect.addFlashAttribute("message", "recurring.saved");

        return "redirect:/recurring";
    }

    private PlanInfo find(String id) {
        return plans.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String form(Model model, PlanForm form, PlanInfo plan) {
        model.addAttribute("plan", plan);
        model.addAttribute("customers", customers.listActive());
        model.addAttribute("contacts", form.customerId() == null || form.customerId().isBlank()
                ? List.of()
                : customers.listContacts(form.customerId()));
        model.addAttribute("selectedContactId", form.contactId());
        model.addAttribute("catalogChoices", catalogChoices(""));
        model.addAttribute("catalogBase", CATALOG_BASE);
        model.addAttribute("intervals", RecurringInterval.values());
        model.addAttribute("statuses", RecurringStatus.values());
        model.addAttribute("periods", form.interval() == null || form.anchorDate() == null
                ? List.of()
                : plans.preview(form.interval(), form.anchorDate(), form.endDate(), PREVIEW_COUNT));
        itemsModel(model, form);

        return FORM_VIEW;
    }

    private void itemsModel(Model model, PlanForm form) {
        model.addAttribute(FORM_ATTRIBUTE, form);
        model.addAttribute("units", units.list());
        model.addAttribute("taxCategories", catalog.taxCategories());
    }
}
