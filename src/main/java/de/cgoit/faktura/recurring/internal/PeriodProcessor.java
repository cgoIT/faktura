package de.cgoit.faktura.recurring.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.recurring.Period;
import de.cgoit.faktura.recurring.PlanInfo;
import de.cgoit.faktura.recurring.RunStatus;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileService;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles one period of one plan in one transaction: the draft, the run record and the advance of the plan belong
 * together. If any step fails, nothing is stored, so a crash never leaves a draft without a run or the other way
 * round, and running the job again cannot create a second draft for the same period.
 */
@Component
public class PeriodProcessor {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * What happened to a period.
     */
    public enum Outcome {
        DRAFT_CREATED, MARKED_SKIPPED, ALREADY_DONE
    }

    private final PlanRepository plans;
    private final RunRepository runs;
    private final InvoiceService invoices;
    private final TaxProfileService taxProfiles;
    private final TextTemplateService texts;
    private final MessageSource messages;
    private final Clock clock;

    public PeriodProcessor(PlanRepository plans, RunRepository runs, InvoiceService invoices,
            TaxProfileService taxProfiles, TextTemplateService texts, MessageSource messages, Clock clock) {
        this.plans = plans;
        this.runs = runs;
        this.invoices = invoices;
        this.taxProfiles = taxProfiles;
        this.texts = texts;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Generates the draft for a period, or only records it as not billed when it is too old to catch up.
     *
     * @param plan        the plan as loaded by the caller
     * @param period      the period to process, must be the next one of the plan
     * @param today       the day of the job run, used as invoice date
     * @param billable    {@code false} for a period that is too old: only a skipped run is stored
     * @throws RuleViolationException if the draft cannot be created (no tax profile, archived customer, ...)
     * @throws PlanChangedException   if the plan was edited since the caller loaded it
     */
    @Transactional
    public Outcome process(PlanInfo plan, Period period, LocalDate today, boolean billable) {
        if (runs.existsForPeriod(plan.id(), period.from())) {
            advance(plan);

            return Outcome.ALREADY_DONE;
        }

        var runId = UUID.randomUUID()
                .toString();
        var now = Instant.now(clock)
                .toString();

        if (!billable) {
            runs.insert(runId, plan.id(), period.from(), period.to(), RunStatus.SKIPPED, null, now);
            advance(plan);

            return Outcome.MARKED_SKIPPED;
        }

        var draft = invoices.createDraft(toInput(plan, period, today));

        runs.insert(runId, plan.id(), period.from(), period.to(), RunStatus.DRAFT, draft.id(), now);
        advance(plan);

        return Outcome.DRAFT_CREATED;
    }

    private void advance(PlanInfo plan) {
        if (!plans.advanceNextPeriodIndex(plan)) {
            throw new PlanChangedException();
        }
    }

    private InvoiceInput toInput(PlanInfo plan, Period period, LocalDate today) {
        var profile = taxProfiles.findValidOn(period.from())
                .orElseThrow(() -> new RuleViolationException("invoice.error.noTaxProfile"));
        var items = new ArrayList<InvoiceItemInput>();

        for (var template : plan.items()) {
            var category = template.taxCategory() != null ? template.taxCategory() : profile.defaultCategory();
            var rate = taxProfiles.carriesRate(category) ? profile.defaultRateBp() : 0;

            items.add(new InvoiceItemInput(template.description(), template.quantityThousandths(),
                    template.unitCode(), template.unitPriceCents(), template.discountBp(), category, rate,
                    template.catalogItemId()));
        }

        var header = plan.headerText() != null
                ? plan.headerText()
                : texts.defaultBody(TextScope.INVOICE, TextKind.HEADER)
                        .orElse(null);
        var footer = plan.footerText() != null
                ? plan.footerText()
                : texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER)
                        .orElse(null);
        var subject = plan.subject() != null ? plan.subject() : plan.name();
        var fullSubject = messages.getMessage("recurring.subject.pattern",
                new Object[] { subject, DATE.format(period.from()), DATE.format(period.to()) }, Locale.GERMAN);

        return new InvoiceInput(plan.customerId(), today, period.from(), period.to(), plan.paymentDays(),
                fullSubject, header, footer, plan.totalDiscount(), items, plan.contactId(), plan.customerReference(), null);
    }
}
