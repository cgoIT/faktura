package de.cgoit.faktura.dunning.web;

import de.cgoit.faktura.dunning.DunningLevelInfo;
import de.cgoit.faktura.dunning.DunningLevelInput;
import de.cgoit.faktura.dunning.DunningLevelService;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.Placeholder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages for the dunning levels: a list and a form per level (when it is suggested, fee, interest, new term and the
 * letter texts).
 */
@Controller
@RequestMapping("/dunning/levels")
public class DunningLevelController {

    private static final String LIST_VIEW = "dunning/levels";
    private static final String FORM_VIEW = "dunning/level-form";
    private static final String REDIRECT_LIST = "redirect:/dunning/levels";

    private final DunningLevelService levels;

    public DunningLevelController(DunningLevelService levels) {
        this.levels = levels;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("levels", levels.list().stream()
                .map(LevelForm::of)
                .toList());

        return LIST_VIEW;
    }

    @GetMapping("/new")
    public String newLevel(Model model) {
        var next = levels.list().stream()
                .mapToInt(DunningLevelInfo::level)
                .max()
                .orElse(0) + 1;

        return form(model, LevelForm.blank(next), null);
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable String id, Model model) {
        var level = find(id);

        return form(model, LevelForm.of(level), level);
    }

    @PostMapping
    public String create(@ModelAttribute LevelForm form, Model model, RedirectAttributes redirect) {
        try {
            levels.create(form.toInput());
            redirect.addFlashAttribute("message", "dunning.level.created");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, null);
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute LevelForm form, Model model,
            RedirectAttributes redirect) {
        var level = find(id);

        try {
            levels.update(id, form.toInput());
            redirect.addFlashAttribute("message", "dunning.level.saved");

            return REDIRECT_LIST;
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());

            return form(model, form, level);
        }
    }

    private String form(Model model, LevelForm form, DunningLevelInfo level) {
        model.addAttribute("levelForm", form);
        model.addAttribute("level", level);
        model.addAttribute("placeholders", Placeholder.forDocuments());

        return FORM_VIEW;
    }

    private DunningLevelInfo find(String id) {
        return levels.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Form values of a level as the user types them: money and percent in German notation.
     */
    public record LevelForm(String id, String level, String name, String daysAfterDue, String fee, String interest,
            String newDueDays, String headerText, String footerText, Boolean active) {

        static LevelForm of(DunningLevelInfo info) {
            return new LevelForm(info.id(), String.valueOf(info.level()), info.name(),
                    String.valueOf(info.daysAfterDue()), DecimalInput.formatCents(info.feeCents()),
                    DecimalInput.formatBasisPoints(info.interestBp()), String.valueOf(info.newDueDays()),
                    info.headerText(), info.footerText(), info.active());
        }

        static LevelForm blank(int level) {
            return new LevelForm(null, String.valueOf(level), "", "", "0,00", "0", "7", "", "", true);
        }

        DunningLevelInput toInput() {
            return new DunningLevelInput(integer(level), name, integer(daysAfterDue), DecimalInput.parseCents(fee),
                    DecimalInput.parseBasisPoints(interest), integer(newDueDays), headerText, footerText, Boolean.TRUE.equals(active));
        }

        private static int integer(String value) {
            try {
                return Integer.parseInt(value.strip());
            } catch (NumberFormatException | NullPointerException e) {
                throw new RuleViolationException("dunning.level.error.number");
            }
        }
    }
}
