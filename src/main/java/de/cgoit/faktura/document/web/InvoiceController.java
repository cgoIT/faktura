package de.cgoit.faktura.document.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import de.cgoit.faktura.archive.ArchiveIntegrityException;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.ShippingOptionService;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.Calculation;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.DunningHints;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.OfferState;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.document.PaymentState;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.FieldViolation;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileService;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages of invoices and offers: list, editor with live totals, save and delete. Offers are served under {@code /offers}
 * by the same handlers: they are documents of another type with the same editor, review and PDF. Live updates are htmx
 * requests that post the whole form and get fragments back; the server keeps no editor state.
 */
@Controller
@RequestMapping({"/invoices", "/offers"})
public class InvoiceController {

    private static final String LIST_VIEW = "invoice/list";
    private static final String EDITOR_VIEW = "invoice/editor";
    private static final String VIEW_VIEW = "invoice/view";
    private static final String FORM_ATTRIBUTE = "invoiceForm";
    private static final String FORM_ERROR = "invoice.error.form";
    private static final int CATALOG_RESULTS = 300;
    private static final String INVOICES_BASE = "/invoices";
    private static final String OFFERS_BASE = "/offers";

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceController.class);

    private final InvoiceService invoices;
    private final PaymentService payments;
    private final ObjectProvider<DunningHints> dunningHints;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final UnitService units;
    private final FinalizeService finalizer;
    private final TextTemplateService texts;
    private final MessageSource messages;
    private final TaxProfileService taxProfiles;
    private final Clock clock;
    private final ShippingOptionService shippingOptions;
    private final OfferService offers;

    public InvoiceController(InvoiceService invoices, PaymentService payments, ObjectProvider<DunningHints> dunningHints,
            FinalizeService finalizer, CustomerService customers, CatalogService catalog, UnitService units,
            TaxProfileService taxProfiles, TextTemplateService texts, MessageSource messages, Clock clock,
            ShippingOptionService shippingOptions, OfferService offers) {
        this.offers = offers;
        this.shippingOptions = shippingOptions;
        this.payments = payments;
        this.dunningHints = dunningHints;
        this.finalizer = finalizer;
        this.texts = texts;
        this.messages = messages;
        this.taxProfiles = taxProfiles;
        this.invoices = invoices;
        this.customers = customers;
        this.catalog = catalog;
        this.units = units;
        this.clock = clock;
    }

    /**
     * The path the templates build their links from: {@code /offers} for an offer page, else {@code /invoices}.
     */
    @ModelAttribute("base")
    String base() {
        return isOfferRequest() ? OFFERS_BASE : INVOICES_BASE;
    }

    /**
     * Tells the templates whether the page shows offers.
     */
    @ModelAttribute("offer")
    boolean offer() {
        return isOfferRequest();
    }

    private static boolean isOfferRequest() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var path = request.getRequestURI().substring(request.getContextPath().length());

        return path.equals(OFFERS_BASE) || path.startsWith(OFFERS_BASE + "/");
    }

    private TextScope textScope() {
        return isOfferRequest() ? TextScope.OFFER : TextScope.INVOICE;
    }

    /**
     * Shows the list. A htmx request (search while typing) gets only the table rows.
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String status,
            @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        if (isOfferRequest()) {
            return offerList(q, status, htmx, model);
        }

        var filter = ListFilter.of(status);
        var suggested = dunningHints.stream()
                .flatMap(hints -> hints.invoiceIdsWithSuggestion()
                        .stream())
                .collect(Collectors.toSet());
        var rows = invoices.list(q, filter.documentStatus()).stream()
                .map(row -> row.withDunningSuggested(suggested.contains(row.id())))
                .filter(filter::matches)
                .toList();

        model.addAttribute("invoices", rows);
        model.addAttribute("q", q);
        model.addAttribute("status", filter.name().toLowerCase(Locale.ROOT));

        return htmx != null ? LIST_VIEW + " :: rows" : LIST_VIEW;
    }

    private String offerList(String q, String status, String htmx, Model model) {
        var state = OfferListFilter.of(status);

        model.addAttribute("offers", offers.list(q, state));
        model.addAttribute("q", q);
        model.addAttribute("status", state == null ? "all" : state.name().toLowerCase(Locale.ROOT));
        model.addAttribute("states", OfferState.values());

        return htmx != null ? "offer/list :: rows" : "offer/list";
    }

    @GetMapping("/new")
    public String newInvoice(Model model) {
        var form = InvoiceForm.empty(LocalDate.now(clock))
                .withTexts(texts.defaultBody(textScope(), TextKind.HEADER).orElse(null),
                        texts.defaultBody(textScope(), TextKind.FOOTER).orElse(null));

        return editor(model, form, null);
    }

    /**
     * Returns the contact person select for a customer; the editor swaps it in when the customer changes.
     */
    @GetMapping("/contact-field")
    public String contactField(@RequestParam(defaultValue = "") String customerId, Model model) {
        model.addAttribute("contacts", customerId.isBlank() ? List.of() : customers.listContacts(customerId));
        model.addAttribute("selectedContactId", null);

        return "invoice/fragments :: contactField";
    }

    @GetMapping("/{id}")
    public String show(@PathVariable String id, Model model) {
        var invoice = find(id);

        if (!invoice.isDraft()) {
            model.addAttribute("invoice", invoice);

            if (isOfferRequest()) {
                offerPanel(model, invoice);
            } else {
                paymentPanel(model, invoice.id());
            }

            return VIEW_VIEW;
        }

        return editor(model, InvoiceForm.from(invoice), invoice);
    }

    /**
     * Saves the posted form content and then finalizes the draft. The {@code reviewed} flag is a guard against
     * finalizing by accident, not proof of a review: any client can send it.
     * After this the invoice has a number and is locked.
     * If the content is invalid, nothing is finalized and the editor shows the entered values with the error. If
     * finalizing fails, the saved draft stays.
     */
    @PostMapping("/{id}/finalize")
    public String finalizeInvoice(@PathVariable String id, InvoiceForm form, BindingResult result, Model model,
            @RequestParam(required = false) String reviewed, RedirectAttributes redirect) {
        var existing = find(id);

        if (!existing.isDraft()) {
            redirect.addFlashAttribute("error", "document.error.notDraft");

            return "redirect:" + base() + "/" + id;
        }

        if (reviewed == null) {
            model.addAttribute("error", "invoice.error.notReviewed");

            return editor(model, form, existing);
        }

        try {
            if (result.hasErrors()) {
                throw new RuleViolationException(FORM_ERROR);
            }

            invoices.updateDraft(id, form.toInput(), form.requireVersion());
        } catch (RuleViolationException e) {
            model.addAttribute("error", reject(e, result));

            return editor(model, form, existing);
        }

        try {
            finalizer.finalizeInvoice(id);
            redirect.addFlashAttribute("message", isOfferRequest() ? "offer.finalized" : "invoice.finalized");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
            redirect.addFlashAttribute("errorDetails", e.details());
        } catch (RuntimeException e) {
            LOG.error("Finalizing document {} failed, it stays a draft", id, e);
            redirect.addFlashAttribute("error", "invoice.error.finalizeFailed");
        }

        return "redirect:" + base() + "/" + id;
    }

    /**
     * Shows the dialog in which the user checks the document before finalizing it. Nothing is stored. A htmx request
     * that targets the summary gets only the summary, which is reloaded when a date in the dialog changes.
     */
    @PostMapping("/{id}/finalize-review")
    public String review(@PathVariable String id, InvoiceForm form, BindingResult result, Model model,
            @RequestHeader(value = "HX-Target", required = false) String target) {
        find(id);

        var today = LocalDate.now(clock);
        var outdated = !"review-summary".equals(target) && form.issueDate() != null && form.issueDate().isBefore(today);

        if (outdated) {
            form = form.withIssueDate(today);
        }

        model.addAttribute("invoiceId", id);
        model.addAttribute("issueDatePreset", outdated);
        model.addAttribute(FORM_ATTRIBUTE, form);

        try {
            if (result.hasErrors()) {
                throw new RuleViolationException(FORM_ERROR);
            }

            var review = finalizer.review(id, form.toInput());

            model.addAttribute("review", review);
            model.addAttribute("headerHtml", RichText.toXhtml(review.headerText()));
            model.addAttribute("footerHtml", RichText.toXhtml(review.footerText()));
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
            model.addAttribute("violationKeys", e.violations().stream()
                    .map(FieldViolation::key)
                    .filter(key -> !key.equals(e.messageKey()))
                    .distinct()
                    .toList());
            model.addAttribute("errorDetails", e.details());
        }

        return "review-summary".equals(target) ? "invoice/review :: summary" : "invoice/review :: dialog";
    }

    /**
     * Records a payment. A htmx request gets the payment panel back, otherwise the page is shown again.
     */
    @PostMapping("/{id}/payments")
    public String recordPayment(@PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "BANK_TRANSFER") String method,
            @RequestParam(required = false) String reference, @RequestParam(required = false) String note,
            @RequestHeader(value = "HX-Request", required = false) String htmx, Model model,
            RedirectAttributes redirect) {
        findInvoice(id);

        try {
            var cents = DecimalInput.parseCents(amount);
            var paymentMethod = parseMethod(method);

            payments.record(id, new PaymentInput(date, cents, paymentMethod, reference, note));

            return paymentResponse(id, htmx, model, redirect, "payment.recorded", null);
        } catch (RuleViolationException e) {
            return paymentResponse(id, htmx, model, redirect, null, e.messageKey());
        }
    }

    /**
     * Corrects a payment with a reversing entry; the payment itself stays in the list.
     */
    @PostMapping("/{id}/payments/{paymentId}/reverse")
    public String reversePayment(@PathVariable String id, @PathVariable String paymentId,
            @RequestHeader(value = "HX-Request", required = false) String htmx, Model model,
            RedirectAttributes redirect) {
        findInvoice(id);

        try {
            payments.reverse(id, paymentId);

            return paymentResponse(id, htmx, model, redirect, "payment.reversed", null);
        } catch (RuleViolationException e) {
            return paymentResponse(id, htmx, model, redirect, null, e.messageKey());
        }
    }

    private String paymentResponse(String id, String htmx, Model model, RedirectAttributes redirect, String message,
            String error) {
        if (htmx == null) {
            redirect.addFlashAttribute("message", message);
            redirect.addFlashAttribute("error", error);

            return "redirect:" + base() + "/" + id;
        }

        model.addAttribute("message", message);
        model.addAttribute("error", error);
        model.addAttribute("invoice", find(id));
        paymentPanel(model, id);

        return "invoice/payments :: panel";
    }

    /**
     * Puts the course of an offer into the model: its state, the events, the invoices created from it and, when
     * creating another invoice deserves a question, the message key of that question.
     */
    private void offerPanel(Model model, InvoiceInfo offer) {
        var state = offers.state(offer.id()).orElseThrow();
        var invoiceIds = offers.invoiceIds(offer.id());
        var confirmKey = !invoiceIds.isEmpty()
                ? "offer.invoice.confirmAgain"
                : (state == OfferState.REJECTED || state == OfferState.EXPIRED ? "offer.invoice.confirmClosed" : null);

        model.addAttribute("offerState", state);
        model.addAttribute("offerEvents", offers.events(offer.id()));
        model.addAttribute("offerInvoiceIds", invoiceIds);
        model.addAttribute("offerConfirmKey", confirmKey);
    }

    private void paymentPanel(Model model, String id) {
        var balance = payments.balance(id);

        model.addAttribute("balance", balance);
        model.addAttribute("payments", payments.list(id));
        model.addAttribute("today", LocalDate.now(clock));
        model.addAttribute("openAmount", balance == null ? "" : DecimalInput.formatCents(balance.openCents()));
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("canRecord", balance != null && balance.state() != PaymentState.CANCELLED);
    }

    private static PaymentMethod parseMethod(String method) {
        try {
            return PaymentMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            throw new RuleViolationException("validation.required");
        }
    }

    /**
     * Cancels a finalized invoice with a cancellation document and opens that document.
     */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable String id, RedirectAttributes redirect) {
        findInvoice(id);

        try {
            var cancellation = finalizer.cancelInvoice(id);
            redirect.addFlashAttribute("message", "invoice.cancelled");

            return "redirect:" + base() + "/" + cancellation.id();
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());

            return "redirect:" + base() + "/" + id;
        } catch (RuntimeException e) {
            LOG.error("Cancelling invoice {} failed", id, e);
            redirect.addFlashAttribute("error", "invoice.error.finalizeFailed");

            return "redirect:" + base() + "/" + id;
        }
    }

    /**
     * Shows the archived PDF of a finalized document.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        find(id);

        try {
            var pdf = finalizer.findPdf(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            return pdfResponse(pdf.filename(), pdf.content());
        } catch (ArchiveIntegrityException e) {
            LOG.error("Archived PDF of document {} failed verification", id, e);

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Shows a preview of the posted form content as PDF; nothing is stored and the document gets no number.
     */
    @PostMapping("/{id}/preview.pdf")
    public ResponseEntity<byte[]> preview(@PathVariable String id, InvoiceForm form, BindingResult result) {
        find(id);

        try {
            if (result.hasErrors()) {
                throw new RuleViolationException(FORM_ERROR);
            }

            return pdfResponse("preview.pdf", finalizer.previewDraft(id, form.toInput()));
        } catch (RuleViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(messages.getMessage(e.messageKey(), null, e.messageKey(), Locale.GERMAN)
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ResponseEntity<byte[]> pdfResponse(String filename, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename)
                        .build()
                        .toString())
                .body(content);
    }

    @PostMapping
    public String create(InvoiceForm form, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, form, result, model, redirect);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, InvoiceForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        return save(find(id), form, result, model, redirect);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        find(id);

        try {
            invoices.deleteDraft(id);
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());

            return "redirect:" + base() + "/" + id;
        }

        redirect.addFlashAttribute("message", "invoice.deleted");

        return "redirect:" + base();
    }

    /**
     * Recalculates the sums from the posted form for the live display. Nothing is saved.
     */
    @PostMapping("/calculate")
    public String calculate(InvoiceForm form, BindingResult result, Model model) {
        liveModel(model, form, result.hasErrors() ? FORM_ERROR : null);

        return "invoice/fragments :: live";
    }

    /**
     * Redraws the item table after the issue date or service period changed: the tax profile of that day decides
     * which tax categories the items may have.
     */
    @PostMapping("/items/refresh")
    public String refreshItems(InvoiceForm form, BindingResult result,
            @RequestParam(defaultValue = "") String catalogQuery, Model model) {
        liveModel(model, form, result.hasErrors() ? FORM_ERROR : null, catalogQuery);

        return "invoice/fragments :: itemsRefresh";
    }

    /**
     * Searches the catalog for the picker of the editor, within the tax profile of the current service day.
     */
    @PostMapping("/catalog/search")
    public String searchCatalog(InvoiceForm form, BindingResult result,
            @RequestParam(defaultValue = "") String catalogQuery, Model model) {
        model.addAttribute("catalogChoices",
                invoices.catalogChoices(form.taxDay(LocalDate.now(clock)), catalogQuery, CATALOG_RESULTS));
        model.addAttribute("catalogBase", base());

        return "fragments/catalog-results :: results(oob=false)";
    }

    @PostMapping("/items/add")
    public String addFreeTextItem(InvoiceForm form, BindingResult result, Model model) {
        return addItem(form, null, result, model);
    }

    @PostMapping("/items/add-catalog")
    public String addCatalogItem(InvoiceForm form, BindingResult result, @RequestParam String catalogItemId,
            Model model) {
        return addItem(form, catalogItemId, result, model);
    }

    @PostMapping("/items/remove")
    public String removeItem(InvoiceForm form, BindingResult result, @RequestParam int index, Model model) {
        var items = form.itemList();

        if (index >= 0 && index < items.size()) {
            items.remove(index);
        }

        liveModel(model, form.withItems(items), result.hasErrors() ? FORM_ERROR : null);

        return "invoice/fragments :: itemsUpdate";
    }

    private String addItem(InvoiceForm form, String catalogItemId, BindingResult result, Model model) {
        var items = form.itemList();
        var error = result.hasErrors() ? FORM_ERROR : null;

        try {
            var proposal = invoices.proposeItem(catalogItemId, form.taxDay(LocalDate.now(clock)));
            items.add(ItemForm.from(proposal));
        } catch (RuleViolationException e) {
            error = e.messageKey();
        }

        liveModel(model, form.withItems(items), error);

        return "invoice/fragments :: itemsUpdate";
    }

    private String save(InvoiceInfo existing, InvoiceForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        if (existing != null && !existing.isDraft()) {
            redirect.addFlashAttribute("error", "document.error.notDraft");

            return "redirect:" + base() + "/" + existing.id();
        }

        try {
            if (result.hasErrors()) {
                throw new RuleViolationException(FORM_ERROR);
            }

            var input = form.toInput();
            var saved = existing == null
                    ? (isOfferRequest() ? invoices.createOfferDraft(input) : invoices.createDraft(input))
                    : invoices.updateDraft(existing.id(), input, form.requireVersion());
            redirect.addFlashAttribute("message", existing == null ? "invoice.created" : "invoice.saved");

            return "redirect:" + base() + "/" + saved.id();
        } catch (RuleViolationException e) {
            model.addAttribute("error", reject(e, result));

            return editor(model, form, existing);
        }
    }

    /**
     * Puts every violation at its field of the form. Returns the key of the notice above the form: the first violation
     * without a field of its own, else the general hint to check the marked fields.
     */
    private static String reject(RuleViolationException e, BindingResult result) {
        String notice = null;

        for (var violation : e.violations()) {
            var field = InvoiceFields.formField(violation.field());

            if (field == null || !rejectValue(result, field, violation.key())) {
                notice = notice != null ? notice : violation.key();
            }
        }

        return notice != null ? notice : FORM_ERROR;
    }

    private static boolean rejectValue(BindingResult result, String field, String key) {
        try {
            result.rejectValue(field, key);

            return true;
        } catch (InvalidPropertyException e) {
            return false;
        }
    }

    private String editor(Model model, InvoiceForm form, InvoiceInfo invoice) {
        if (!model.containsAttribute(FORM_ATTRIBUTE)) {
            model.addAttribute(FORM_ATTRIBUTE, form);
        }

        liveModel(model, form, null);
        model.addAttribute("invoice", invoice);
        model.addAttribute("customers", selectableCustomers(form.customerId()));
        model.addAttribute("contacts", form.customerId() == null || form.customerId().isBlank()
                ? List.of()
                : customers.listContacts(form.customerId()));
        model.addAttribute("selectedContactId", form.contactId());
        model.addAttribute("shippingOptions", shippingOptions.listActive().stream()
                .map(option -> new ShippingChoice(option.id(), option.name(),
                        DecimalInput.formatCents(option.amountCents()), option.taxCategory(),
                        DecimalInput.formatBasisPoints(option.taxRateBp())))
                .toList());
        model.addAttribute("headerTemplates", texts.list(textScope(), TextKind.HEADER));
        model.addAttribute("footerTemplates", texts.list(textScope(), TextKind.FOOTER));

        return EDITOR_VIEW;
    }

    /**
     * Puts everything the item table and the totals need into the model. A form that cannot be calculated (invalid
     * number) still renders, without sums.
     */
    private void liveModel(Model model, InvoiceForm form, String error) {
        liveModel(model, form, error, "");
    }

    private void liveModel(Model model, InvoiceForm form, String error, String catalogQuery) {
        model.addAttribute(FORM_ATTRIBUTE, form);
        model.addAttribute("units", units.list());
        model.addAttribute("taxCategories", catalog.taxCategories());
        model.addAttribute("allowedCategories", invoices.allowedCategories(form.taxDay(LocalDate.now(clock))));
        model.addAttribute("catalogChoices",
                invoices.catalogChoices(form.taxDay(LocalDate.now(clock)), catalogQuery, CATALOG_RESULTS));
        model.addAttribute("catalogBase", base());
        model.addAttribute("ratedCategories", catalog.taxCategories().stream()
                .filter(taxProfiles::carriesRate)
                .toList());
        model.addAttribute("liveError", error);

        Calculation calculation = null;

        try {
            calculation = invoices.calculate(form.toInput());
        } catch (RuleViolationException e) {
            model.addAttribute("liveError", error != null ? error : e.messageKey());
        }

        model.addAttribute("calc", calculation);
        model.addAttribute("showTax", calculation != null && calculation.taxTotals().stream()
                .anyMatch(total -> total.rateBp() > 0));
    }

    private List<CustomerInfo> selectableCustomers(String selectedId) {
        var active = new ArrayList<>(customers.listActive());

        if (selectedId != null && active.stream().noneMatch(customer -> customer.id().equals(selectedId))) {
            customers.find(selectedId)
                    .ifPresent(active::add);
        }

        return active;
    }

    /**
     * A shipping option as the editor offers it: amount and rate already in German notation.
     */
    public record ShippingChoice(String id, String name, String amount, String taxCategory, String taxRate) {
    }

    /**
     * Finds the document of the page's kind: under {@code /offers} only offers, under {@code /invoices} only invoices
     * and cancellations.
     */
    private InvoiceInfo find(String id) {
        return invoices.find(id)
                .filter(found -> (found.type() == DocumentType.OFFER) == isOfferRequest())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Finds an invoice or cancellation for an action that only exists for those, such as payments.
     */
    private InvoiceInfo findInvoice(String id) {
        if (isOfferRequest()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return find(id);
    }

    /**
     * Filter of the offer list: by derived state.
     */
    private static final class OfferListFilter {

        private OfferListFilter() {
        }

        static OfferState of(String value) {
            try {
                return OfferState.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Filters of the invoice list: by display status or by payment situation.
     */
    private enum ListFilter {
        ALL, DRAFT, FINAL, CANCELLED, OPEN, PARTIAL, PAID, OVERDUE, SUGGESTED;

        static ListFilter of(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }

        DocumentStatus documentStatus() {
            return switch (this) {
                case DRAFT -> DocumentStatus.DRAFT;
                case CANCELLED -> DocumentStatus.CANCELLED;
                case FINAL -> DocumentStatus.FINAL;
                default -> null;
            };
        }

        boolean matches(InvoiceSummary row) {
            var balance = row.balance();

            return switch (this) {
                case OPEN -> balance != null && (balance.state() == PaymentState.OPEN
                        || balance.state() == PaymentState.PARTIALLY_PAID);
                case PARTIAL -> balance != null && balance.state() == PaymentState.PARTIALLY_PAID;
                case PAID -> balance != null && balance.state() == PaymentState.PAID;
                case OVERDUE -> balance != null && balance.overdue();
                case SUGGESTED -> row.dunningSuggested();
                default -> true;
            };
        }
    }
}
