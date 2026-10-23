package de.cgoit.faktura.catalog.web;

import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page to maintain the units of measure. Every row is its own small form.
 */
@Controller
@RequestMapping("/units")
public class UnitController {

    private final UnitService units;

    public UnitController(UnitService units) {
        this.units = units;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("units", units.list());

        return "catalog/units";
    }

    @PostMapping
    public String create(@RequestParam String code, @RequestParam String name,
            @RequestParam(defaultValue = "") String namePlural, @RequestParam String uneceCode,
            RedirectAttributes redirect) {
        return handle(redirect, "unit.created", () -> units.create(code, name, namePlural, uneceCode));
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @RequestParam String code, @RequestParam String name,
            @RequestParam(defaultValue = "") String namePlural, @RequestParam String uneceCode,
            RedirectAttributes redirect) {
        return handle(redirect, "unit.saved", () -> units.update(id, code, name, namePlural, uneceCode));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        return handle(redirect, "unit.deleted", () -> units.delete(id));
    }

    private String handle(RedirectAttributes redirect, String successKey, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("message", successKey);
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/units";
    }
}
