package de.cgoit.faktura.company.web;

import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page for the company master data.
 */
@Controller
@RequestMapping("/company")
public class CompanyController {

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @GetMapping
    public String show(Model model) {
        var form = companies.find()
                .map(CompanyForm::from)
                .orElseGet(CompanyForm::empty);
        model.addAttribute("companyForm", form);

        return "company/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("companyForm") CompanyForm form, BindingResult result,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return "company/form";
        }

        try {
            companies.save(form.toInfo());
        } catch (RuleViolationException e) {
            result.rejectValue(e.field() == null ? "iban" : e.field(), e.messageKey());

            return "company/form";
        }

        redirect.addFlashAttribute("message", "company.saved");

        return "redirect:/company";
    }
}
