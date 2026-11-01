package de.cgoit.faktura.mail.web;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.mail.MailOwnerType;
import de.cgoit.faktura.mail.MailPreview;
import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailService;
import de.cgoit.faktura.mail.MailTarget;
import de.cgoit.faktura.mail.MailTemplateInfo;
import de.cgoit.faktura.mail.MailTemplateService;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.Placeholder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages to maintain the mail templates, with a live preview of the HTML part, the plain text part and the values of
 * the placeholders. The preview never saves anything.
 */
@Controller
@RequestMapping("/mail-templates")
public class MailTemplateController {

    private static final String LIST_VIEW = "mail/template-list";
    private static final String FORM_VIEW = "mail/template-form";
    private static final String REDIRECT_LIST = "redirect:/mail-templates";
    private static final int MAX_PREVIEW_SOURCES = 30;
    private static final List<MailScope> EDITABLE_SCOPES = List.of(MailScope.INVOICE, MailScope.CANCELLATION,
            MailScope.OFFER, MailScope.DUNNING);

    private final MailTemplateService templates;
    private final MailService mail;
    private final InvoiceService invoices;
    private final OfferService offers;
    private final DunningService dunnings;

    public MailTemplateController(MailTemplateService templates, MailService mail, InvoiceService invoices,
            OfferService offers, DunningService dunnings) {
        this.templates = templates;
        this.mail = mail;
        this.invoices = invoices;
        this.offers = offers;
        this.dunnings = dunnings;
    }

    @GetMapping
    public String list(Model model) {
        var sorted = templates.list().stream()
                .sorted(Comparator.comparing(MailTemplateInfo::scope)
                        .thenComparing(MailTemplateInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        model.addAttribute("templates", sorted.stream()
                .filter(template -> !template.scope().system())
                .toList());
        model.addAttribute("systemTemplates", sorted.stream()
                .filter(template -> template.scope().system())
                .toList());
        model.addAttribute("status", mail.status());

        return LIST_VIEW;
    }

    @GetMapping("/new")
    public String newTemplate(@RequestParam(defaultValue = "INVOICE") MailScope scope,
            @RequestParam(required = false) String copy, Model model) {
        var form = templates.find(copy)
                .filter(source -> !source.scope().system())
                .map(source -> new MailTemplateForm(source.scope(), source.name(), source.subject(), source.body(),
                        Boolean.FALSE, source.tone()))
                .orElse(MailTemplateForm.empty(scope));

        return form(model, form, null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var template = find(id);

        return form(model, new MailTemplateForm(template.scope(), template.name(), template.subject(),
                template.body(), template.isDefault(), template.tone()), template);
    }

    @PostMapping
    public String create(MailTemplateForm form, Model model, RedirectAttributes redirect) {
        try {
            templates.create(form.toInput());
            redirect.addFlashAttribute("message", "mail.template.created");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, null);
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, MailTemplateForm form, Model model, RedirectAttributes redirect) {
        var template = find(id);

        try {
            templates.update(id, form.toInput());
            redirect.addFlashAttribute("message", "mail.template.saved");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, template);
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        find(id);
        templates.delete(id);
        redirect.addFlashAttribute("message", "mail.template.deleted");

        return REDIRECT_LIST;
    }

    /**
     * Renders the template as typed into the form, with sample values or the values of a chosen document. Nothing
     * is stored.
     *
     * @param source {@code DOCUMENT:<id>} or {@code DUNNING:<id>}; blank for sample values
     */
    @PostMapping("/preview")
    public String preview(@RequestParam(defaultValue = "") String subject, @RequestParam(defaultValue = "") String body,
            @RequestParam(defaultValue = "") String source, Model model) {
        try {
            var preview = mail.previewTemplate(subject, body, target(source));

            model.addAttribute("preview", preview);
            model.addAttribute("values", plainValues(preview));
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
        }

        return "mail/preview :: preview";
    }

    private String form(Model model, MailTemplateForm form, MailTemplateInfo template) {
        model.addAttribute("mailTemplateForm", form);
        model.addAttribute("template", template);
        model.addAttribute("scopes", form.scope() != null && form.scope().system()
                ? List.of(form.scope())
                : EDITABLE_SCOPES);
        model.addAttribute("system", form.scope() != null && form.scope().system());
        model.addAttribute("placeholders", form.scope() == MailScope.NOTIFICATION_DIGEST
                ? Placeholder.forDigest()
                : Placeholder.forMails());
        model.addAttribute("sourceGroups", previewSources());

        return FORM_VIEW;
    }

    /**
     * Lists the documents whose values can be used for the preview, newest first, grouped by document type.
     */
    private Map<MailScope, List<PreviewSource>> previewSources() {
        var groups = new LinkedHashMap<MailScope, List<PreviewSource>>();
        var documents = invoices.list("", null).stream()
                .filter(row -> row.status() != DocumentStatus.DRAFT && row.number() != null)
                .sorted(Comparator.comparing(InvoiceSummary::issueDate).reversed())
                .toList();

        groups.put(MailScope.INVOICE, documents.stream()
                .filter(row -> row.type() == DocumentType.INVOICE)
                .limit(MAX_PREVIEW_SOURCES)
                .map(row -> new PreviewSource("DOCUMENT:" + row.id(), row.number() + " – " + row.customerName()))
                .toList());
        groups.put(MailScope.CANCELLATION, documents.stream()
                .filter(row -> row.type() == DocumentType.CANCELLATION)
                .limit(MAX_PREVIEW_SOURCES)
                .map(row -> new PreviewSource("DOCUMENT:" + row.id(), row.number() + " – " + row.customerName()))
                .toList());
        groups.put(MailScope.OFFER, offers.list("", null).stream()
                .filter(row -> row.status() != DocumentStatus.DRAFT && row.number() != null)
                .limit(MAX_PREVIEW_SOURCES)
                .map(row -> new PreviewSource("DOCUMENT:" + row.id(), row.number() + " – " + row.customerName()))
                .toList());
        groups.put(MailScope.DUNNING, dunnings.list().stream()
                .limit(MAX_PREVIEW_SOURCES)
                .map(row -> new PreviewSource("DUNNING:" + row.dunning().id(),
                        row.dunning().number() + " – " + row.customerName()))
                .toList());
        groups.values().removeIf(List::isEmpty);

        return groups;
    }

    private static MailTarget target(String source) {
        if (source == null || source.isBlank() || !source.contains(":")) {
            return null;
        }

        var parts = source.split(":", 2);

        try {
            return new MailTarget(MailOwnerType.valueOf(parts[0]), parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Values as shown to the user: the markup escapes are resolved, so a name prints as it is.
     */
    private static Map<String, String> plainValues(MailPreview preview) {
        var values = new LinkedHashMap<String, String>();

        for (var placeholder : Placeholder.values()) {
            if (preview.values().containsKey(placeholder)) {
                values.put(placeholder.key(), RichText.toPlainText(preview.values().get(placeholder)));
            }
        }

        return values;
    }

    private MailTemplateInfo find(String id) {
        return templates.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * A document offered in the preview selection.
     */
    public record PreviewSource(String value, String label) {
    }
}
