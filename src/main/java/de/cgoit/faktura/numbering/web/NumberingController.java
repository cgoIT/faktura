package de.cgoit.faktura.numbering.web;

import java.time.Clock;
import java.time.LocalDate;

import de.cgoit.faktura.numbering.NumberScope;
import de.cgoit.faktura.numbering.NumberSettingsService;
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
 * Page to configure the number pattern of every scope.
 */
@Controller
@RequestMapping("/numbering")
public class NumberingController {

    private final NumberSettingsService settings;
    private final Clock clock;

    public NumberingController(NumberSettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("patterns", settings.list(LocalDate.now(clock)));

        return "numbering/list";
    }

    @PostMapping("/{scope}")
    public String update(@PathVariable NumberScope scope, @RequestParam String pattern, RedirectAttributes redirect) {
        try {
            settings.update(scope, pattern);
            redirect.addFlashAttribute("message", "numbering.saved");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "numbering.error.invalid");
        }

        return "redirect:/numbering";
    }
}
