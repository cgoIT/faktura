package de.cgoit.faktura.home;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.home.internal.SetupStepRepository;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * State of the initial set-up. Steps that follow from the data are derived each time; only the manual steps are
 * stored.
 */
@Service
public class SetupService {

    private final SetupStepRepository steps;
    private final CompanyService companies;
    private final TaxProfileService taxProfiles;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final InvoiceService invoices;
    private final Clock clock;

    public SetupService(SetupStepRepository steps, CompanyService companies, TaxProfileService taxProfiles,
            CatalogService catalog, CustomerService customers, InvoiceService invoices, Clock clock) {
        this.steps = steps;
        this.companies = companies;
        this.taxProfiles = taxProfiles;
        this.catalog = catalog;
        this.customers = customers;
        this.invoices = invoices;
        this.clock = clock;
    }

    /**
     * Lists all steps in order with their state.
     */
    @Transactional(readOnly = true)
    public List<SetupItem> items() {
        var ticked = steps.findDone();

        return Arrays.stream(SetupStep.values())
                .map(step -> new SetupItem(step, step.manual() ? ticked.contains(step.name()) : derived(step)))
                .toList();
    }

    /**
     * Tells whether every step is done.
     */
    @Transactional(readOnly = true)
    public boolean complete() {
        return items().stream()
                .allMatch(SetupItem::done);
    }

    /**
     * Ticks a manual step off or reopens it.
     *
     * @throws RuleViolationException if the step follows from the data and cannot be ticked by hand
     */
    @Transactional
    public void tick(SetupStep step, boolean done) {
        if (!step.manual()) {
            throw new RuleViolationException("home.error.derivedStep");
        }

        if (done) {
            steps.markDone(step.name());
        } else {
            steps.markOpen(step.name());
        }
    }

    private boolean derived(SetupStep step) {
        return switch (step) {
            case COMPANY -> companies.find().isPresent();
            case TAX_PROFILE -> taxProfiles.findValidOn(LocalDate.now(clock)).isPresent();
            case CATALOG -> !catalog.search("", false).isEmpty();
            case CUSTOMERS -> !customers.search("", CustomerStatus.ALL).isEmpty();
            case INVOICE -> !invoices.list("", null).isEmpty();
            default -> false;
        };
    }
}
