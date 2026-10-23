package de.cgoit.faktura.home.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import de.cgoit.faktura.archive.ArchiveExportService;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.DunningHints;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.OfferState;
import de.cgoit.faktura.home.SetupService;
import de.cgoit.faktura.home.SetupStep;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Start page: key figures, the latest documents and the set-up checklist.
 */
@Controller
public class HomeController {

    private static final int LATEST = 5;

    private final SetupService setup;
    private final InvoiceService invoices;
    private final OfferService offers;
    private final ObjectProvider<DunningHints> dunningHints;
    private final ArchiveExportService archiveExport;
    private final Clock clock;

    public HomeController(SetupService setup, InvoiceService invoices, OfferService offers,
            ObjectProvider<DunningHints> dunningHints, ArchiveExportService archiveExport, Clock clock) {
        this.archiveExport = archiveExport;
        this.dunningHints = dunningHints;
        this.setup = setup;
        this.invoices = invoices;
        this.offers = offers;
        this.clock = clock;
    }

    @GetMapping("/")
    public String index(Model model) {
        var year = LocalDate.now(clock).getYear();
        var all = invoices.list("", null);
        var issued = all.stream()
                .filter(row -> row.type() == DocumentType.INVOICE && row.status() == DocumentStatus.FINAL)
                .filter(row -> row.issueDate().getYear() == year)
                .toList();

        model.addAttribute("year", String.valueOf(year));
        model.addAttribute("draftCount", all.stream()
                .filter(row -> row.type() == DocumentType.INVOICE && row.status() == DocumentStatus.DRAFT)
                .count());
        model.addAttribute("issuedCount", issued.size());
        model.addAttribute("issuedCents", issued.stream()
                .mapToLong(InvoiceSummary::grossTotalCents)
                .sum());
        model.addAttribute("cancellationCount", all.stream()
                .filter(row -> row.type() == DocumentType.CANCELLATION)
                .count());
        var overdue = all.stream()
                .filter(row -> row.balance() != null && row.balance().overdue())
                .toList();

        model.addAttribute("overdueCount", overdue.size());
        model.addAttribute("overdueCents", overdue.stream()
                .mapToLong(row -> row.balance().openCents())
                .sum());
        model.addAttribute("suggestionCount", dunningHints.stream()
                .mapToLong(hints -> hints.invoiceIdsWithSuggestion().size())
                .sum());
        model.addAttribute("openOfferCount", offers.list("", null).stream()
                .filter(row -> row.state() == OfferState.OPEN || row.state() == OfferState.SENT)
                .count());
        model.addAttribute("expiringOfferCount", offers.expiringSoon().size());
        model.addAttribute("expiringDays", offers.expiringDays());
        model.addAttribute("exportOpen", archiveExport.status().open());
        model.addAttribute("latest", latest(all));
        model.addAttribute("setupItems", setup.items());
        model.addAttribute("setupComplete", setup.complete());

        return "index";
    }

    /**
     * Ticks a manual set-up step off or reopens it.
     */
    @PostMapping("/setup/{step}")
    public String tick(@PathVariable SetupStep step, @RequestParam(defaultValue = "false") boolean done,
            RedirectAttributes redirect) {
        try {
            setup.tick(step, done);
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/";
    }

    private static List<InvoiceSummary> latest(List<InvoiceSummary> all) {
        return all.stream()
                .sorted(Comparator.comparing(InvoiceSummary::issueDate).reversed()
                        .thenComparing(InvoiceSummary::id))
                .limit(LATEST)
                .toList();
    }
}
