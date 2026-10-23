package de.cgoit.faktura.home.web;

import java.time.Clock;
import java.time.LocalDate;

import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.tax.TaxProfileService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the tax profile that is valid today and the number of recurring drafts waiting for approval to every page for
 * the sidebar.
 */
@ControllerAdvice
public class SidebarAdvice {

    private final TaxProfileService taxProfiles;
    private final RecurringPlanService recurring;
    private final Clock clock;

    public SidebarAdvice(TaxProfileService taxProfiles, RecurringPlanService recurring, Clock clock) {
        this.taxProfiles = taxProfiles;
        this.recurring = recurring;
        this.clock = clock;
    }

    @ModelAttribute("currentTaxProfile")
    TaxProfileInfo currentTaxProfile() {
        return taxProfiles.findValidOn(LocalDate.now(clock)).orElse(null);
    }

    @ModelAttribute("pendingRecurring")
    long pendingRecurring() {
        return recurring.pendingCount();
    }
}
