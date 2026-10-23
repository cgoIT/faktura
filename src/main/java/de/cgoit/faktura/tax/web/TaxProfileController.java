package de.cgoit.faktura.tax.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileService;
import de.cgoit.faktura.tax.TaxRegime;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pages for the tax profiles: the list over time with the exemption reasons, and a form to start a new profile with
 * a live preview of its effect.
 */
@Controller
@RequestMapping("/company/tax-profiles")
public class TaxProfileController {

    private static final String LIST_VIEW = "tax/profiles";
    private static final String FORM_VIEW = "tax/profile-form";
    private static final String REDIRECT_LIST = "redirect:/company/tax-profiles";
    private static final String REASON_PREFIX = "reason.";

    private final TaxProfileService taxProfiles;
    private final CompanyService companies;
    private final Clock clock;

    public TaxProfileController(TaxProfileService taxProfiles, CompanyService companies, Clock clock) {
        this.taxProfiles = taxProfiles;
        this.companies = companies;
        this.clock = clock;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("profiles", taxProfiles.list());
        model.addAttribute("companyMissing", companies.find().isEmpty());
        model.addAttribute("reasons", taxProfiles.categoryReasons());
        model.addAttribute("today", LocalDate.now(clock));

        return LIST_VIEW;
    }

    @GetMapping("/new")
    public String newProfile(Model model) {
        if (companies.find().isEmpty()) {
            return REDIRECT_LIST;
        }

        return form(model, new TaxProfileForm(TaxRegime.SMALL_BUSINESS, LocalDate.now(clock)));
    }

    @GetMapping("/preview")
    public String preview(@RequestParam TaxRegime regime, Model model) {
        model.addAttribute("preview", taxProfiles.preview(regime));

        return FORM_VIEW + " :: preview";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("taxProfileForm") TaxProfileForm form, BindingResult result,
            Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return form(model, form);
        }

        try {
            taxProfiles.create(form.toRequest());
        } catch (RuleViolationException e) {
            result.reject(e.messageKey());

            return form(model, form);
        }

        redirect.addFlashAttribute("message", "tax.created");

        return REDIRECT_LIST;
    }

    @PostMapping("/reasons")
    public String saveReasons(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        var texts = new HashMap<String, String>();

        params.forEach((name, value) -> {
            if (name.startsWith(REASON_PREFIX)) {
                texts.put(name.substring(REASON_PREFIX.length()), value);
            }
        });

        try {
            taxProfiles.saveCategoryReasons(texts);
            redirect.addFlashAttribute("message", "tax.reasons.saved");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return REDIRECT_LIST;
    }

    private String form(Model model, TaxProfileForm form) {
        var regime = form.regime() == null ? TaxRegime.SMALL_BUSINESS : form.regime();
        model.addAttribute("taxProfileForm", form);
        model.addAttribute("preview", taxProfiles.preview(regime));
        model.addAttribute("regimes", TaxRegime.values());

        return FORM_VIEW;
    }
}
