package de.cgoit.faktura.home.web;

import java.time.Clock;
import java.time.LocalDate;

import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.tax.TaxProfileService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the tax profile that is valid today and the number of recurring drafts waiting for approval to every page for
 * the sidebar, and the application version with the commit for the footer.
 */
@ControllerAdvice
public class SidebarAdvice {

    private final TaxProfileService taxProfiles;
    private final RecurringPlanService recurring;
    private final Clock clock;
    private final ObjectProvider<BuildProperties> build;

    public SidebarAdvice(TaxProfileService taxProfiles, RecurringPlanService recurring, Clock clock,
            ObjectProvider<BuildProperties> build) {
        this.taxProfiles = taxProfiles;
        this.recurring = recurring;
        this.clock = clock;
        this.build = build;
    }

    @ModelAttribute("currentTaxProfile")
    TaxProfileInfo currentTaxProfile() {
        return taxProfiles.findValidOn(LocalDate.now(clock)).orElse(null);
    }

    @ModelAttribute("pendingRecurring")
    long pendingRecurring() {
        return recurring.pendingCount();
    }

    @ModelAttribute("appVersion")
    String appVersion() {
        var properties = build.getIfAvailable();

        if (properties == null) {
            return null;
        }

        var commit = properties.get("commit");

        return commit == null || commit.isBlank() || commit.equals("unknown") || commit.startsWith("$")
                ? properties.getVersion()
                : properties.getVersion() + " (" + commit + ")";
    }
}
