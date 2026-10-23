package de.cgoit.faktura.catalog.web;

import de.cgoit.faktura.catalog.CatalogItemInfo;
import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.FieldViolation;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages of the service catalog: searchable list, form to create and change entries, activate and deactivate.
 */
@Controller
@RequestMapping("/catalog")
public class CatalogController {

    private static final String FORM_VIEW = "catalog/form";

    private final CatalogService catalog;
    private final UnitService units;

    public CatalogController(CatalogService catalog, UnitService units) {
        this.catalog = catalog;
        this.units = units;
    }

    /**
     * Shows the list. A htmx request (search while typing) gets only the table rows.
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "active") String status,
            @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        model.addAttribute("items", catalog.search(q, !"all".equals(status)));
        model.addAttribute("q", q);
        model.addAttribute("status", "all".equals(status) ? "all" : "active");

        return htmx != null ? "catalog/list :: rows" : "catalog/list";
    }

    @GetMapping("/new")
    public String newItem(Model model) {
        return form(model, CatalogItemForm.empty(), null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var item = find(id);

        return form(model, CatalogItemForm.from(item), item);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("catalogItemForm") CatalogItemForm form, BindingResult result,
            Model model, RedirectAttributes redirect) {
        return save(null, form, result, model, redirect);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @Valid @ModelAttribute("catalogItemForm") CatalogItemForm form,
            BindingResult result, Model model, RedirectAttributes redirect) {
        return save(find(id), form, result, model, redirect);
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable String id, RedirectAttributes redirect) {
        find(id);
        catalog.deactivate(id);
        redirect.addFlashAttribute("message", "catalog.deactivated");

        return "redirect:/catalog";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable String id, RedirectAttributes redirect) {
        find(id);
        catalog.activate(id);
        redirect.addFlashAttribute("message", "catalog.activated");

        return "redirect:/catalog/" + id;
    }

    private String save(CatalogItemInfo existing, CatalogItemForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return form(model, form, existing);
        }

        try {
            var input = new CatalogItemInput(form.name(), form.description(), form.unitId(),
                    DecimalInput.parseCents(form.price()), form.taxCategory());
            var saved = existing == null ? catalog.create(input) : catalog.update(existing.id(), input);
            redirect.addFlashAttribute("message", existing == null ? "catalog.created" : "catalog.saved");

            return "redirect:/catalog/" + saved.id();
        } catch (RuleViolationException e) {
            e.violations().forEach(violation -> result.rejectValue(formField(violation), violation.key()));

            return form(model, form, existing);
        }
    }

    /**
     * Maps a property of the input to the field of the form: the same name, except the price, which the form shows in
     * euros.
     */
    private static String formField(String property) {
        return "unitPriceCents".equals(property) ? "price" : property;
    }

    private static String formField(FieldViolation violation) {
        if (violation.field() != null) {
            return formField(violation.field());
        }

        return "validation.money".equals(violation.key()) ? "price" : "name";
    }

    private String form(Model model, CatalogItemForm form, CatalogItemInfo item) {
        if (!model.containsAttribute("catalogItemForm")) {
            model.addAttribute("catalogItemForm", form);
        }

        model.addAttribute("item", item);
        model.addAttribute("units", units.list());
        model.addAttribute("taxCategories", catalog.taxCategories());

        return FORM_VIEW;
    }

    private CatalogItemInfo find(String id) {
        return catalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
