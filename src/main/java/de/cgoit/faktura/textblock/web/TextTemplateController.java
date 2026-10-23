package de.cgoit.faktura.textblock.web;

import java.util.Comparator;
import java.util.List;

import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.Placeholder;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateInfo;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages to maintain the reusable header and footer texts of documents.
 */
@Controller
@RequestMapping("/text-templates")
public class TextTemplateController {

    private static final String LIST_VIEW = "textblock/list";
    private static final String FORM_VIEW = "textblock/form";
    private static final String REDIRECT_LIST = "redirect:/text-templates";

    private final TextTemplateService templates;

    public TextTemplateController(TextTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public String list(Model model) {
        var all = templates.list();

        model.addAttribute("headers", ofKind(all, TextKind.HEADER));
        model.addAttribute("footers", ofKind(all, TextKind.FOOTER));

        return LIST_VIEW;
    }

    private static List<TextTemplateInfo> ofKind(List<TextTemplateInfo> all, TextKind kind) {
        return all.stream()
                .filter(template -> template.kind() == kind)
                .sorted(Comparator.comparing(TextTemplateInfo::scope)
                        .thenComparing(TextTemplateInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/new")
    public String newTemplate(Model model) {
        return form(model, TextTemplateForm.empty(), null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var template = find(id);

        return form(model, new TextTemplateForm(template.scope(), template.kind(), template.name(), template.body(),
                template.isDefault()), template);
    }

    @PostMapping
    public String create(TextTemplateForm form, Model model, RedirectAttributes redirect) {
        try {
            templates.create(form.toInput());
            redirect.addFlashAttribute("message", "text.created");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, null);
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, TextTemplateForm form, Model model, RedirectAttributes redirect) {
        var template = find(id);

        try {
            templates.update(id, form.toInput());
            redirect.addFlashAttribute("message", "text.saved");

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
        redirect.addFlashAttribute("message", "text.deleted");

        return REDIRECT_LIST;
    }

    private String form(Model model, TextTemplateForm form, TextTemplateInfo template) {
        model.addAttribute("textTemplateForm", form);
        model.addAttribute("template", template);
        model.addAttribute("scopes", TextScope.values());
        model.addAttribute("kinds", TextKind.values());
        model.addAttribute("placeholders", Placeholder.forDocuments());

        return FORM_VIEW;
    }

    private TextTemplateInfo find(String id) {
        return templates.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
