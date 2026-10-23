package de.cgoit.faktura.catalog.web;

import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.ShippingOptionInfo;
import de.cgoit.faktura.catalog.ShippingOptionInput;
import de.cgoit.faktura.catalog.ShippingOptionService;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pages for the shipping options: a list and a form per option (name, net amount, tax category and rate, order).
 */
@Controller
@RequestMapping("/settings/shipping")
public class ShippingOptionController {

    private static final String LIST_VIEW = "catalog/shipping";
    private static final String FORM_VIEW = "catalog/shipping-form";
    private static final String REDIRECT_LIST = "redirect:/settings/shipping";

    private final ShippingOptionService options;
    private final CatalogService catalog;

    public ShippingOptionController(ShippingOptionService options, CatalogService catalog) {
        this.options = options;
        this.catalog = catalog;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("options", options.list().stream()
                .map(OptionForm::of)
                .toList());

        return LIST_VIEW;
    }

    @GetMapping("/new")
    public String newOption(Model model) {
        return form(model, OptionForm.blank(), null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var option = find(id);

        return form(model, OptionForm.of(option), option);
    }

    @PostMapping
    public String create(@ModelAttribute OptionForm form, Model model, RedirectAttributes redirect) {
        try {
            options.create(form.toInput());
            redirect.addFlashAttribute("message", "shipping.created");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, null);
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute OptionForm form, Model model,
            RedirectAttributes redirect) {
        var option = find(id);

        try {
            options.update(id, form.toInput());
            redirect.addFlashAttribute("message", "shipping.saved");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, option);
        }
    }

    private String form(Model model, OptionForm form, ShippingOptionInfo option) {
        model.addAttribute("optionForm", form);
        model.addAttribute("option", option);
        model.addAttribute("taxCategories", catalog.taxCategories());

        return FORM_VIEW;
    }

    private ShippingOptionInfo find(String id) {
        return options.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Form values of an option as the user types them: money and percent in German notation.
     */
    public record OptionForm(String id, String name, String amount, String taxCategory, String taxRate,
            String sortOrder, Boolean active) {

        static OptionForm of(ShippingOptionInfo info) {
            return new OptionForm(info.id(), info.name(), DecimalInput.formatCents(info.amountCents()),
                    info.taxCategory(), DecimalInput.formatBasisPoints(info.taxRateBp()),
                    String.valueOf(info.sortOrder()), info.active());
        }

        static OptionForm blank() {
            return new OptionForm(null, "", "0,00", "", "0", "0", true);
        }

        ShippingOptionInput toInput() {
            return new ShippingOptionInput(name == null ? "" : name, DecimalInput.parseCents(amount), taxCategory,
                    DecimalInput.parseBasisPoints(taxRate), integer(sortOrder), Boolean.TRUE.equals(active));
        }

        private static int integer(String value) {
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException | NullPointerException e) {
                throw new RuleViolationException("shipping.error.sortOrder");
            }
        }
    }
}
