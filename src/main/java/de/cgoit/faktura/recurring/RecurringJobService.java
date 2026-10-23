package de.cgoit.faktura.recurring;

import java.time.LocalDate;

import de.cgoit.faktura.recurring.internal.PeriodProcessor;
import de.cgoit.faktura.recurring.internal.PlanChangedException;
import de.cgoit.faktura.recurring.internal.PeriodProcessor.Outcome;
import de.cgoit.faktura.recurring.internal.PlanRepository;
import de.cgoit.faktura.recurring.internal.RecurringProperties;
import de.cgoit.faktura.shared.RuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The daily job: generates invoice drafts for periods that start within the lead time of an active plan. It never
 * finalizes or sends anything; the drafts wait on the page "Zur Freigabe".
 *
 * <p>Missed periods are caught up only while they started at most {@code catchUpDays} ago; older ones are recorded as
 * skipped so they are neither billed late by surprise nor looked at again. Each period is processed in its own
 * transaction, so a failing plan does not block the others and a repeated run creates nothing twice.
 */
@Service
public class RecurringJobService {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringJobService.class);

    private static final String UNEXPECTED_ERROR = "recurring.error.unexpected";

    private final PlanRepository plans;
    private final PeriodProcessor processor;
    private final RecurringProperties properties;

    public RecurringJobService(PlanRepository plans, PeriodProcessor processor, RecurringProperties properties) {
        this.plans = plans;
        this.processor = processor;
        this.properties = properties;
    }

    /**
     * Generates all drafts that are due on the given day.
     *
     * @return number of drafts created
     */
    public int runDue(LocalDate today) {
        var created = 0;

        for (var row : plans.findByStatus(RecurringStatus.ACTIVE)) {
            created += runPlan(row.id(), today);
        }

        return created;
    }

    private int runPlan(String planId, LocalDate today) {
        var created = 0;

        while (true) {
            var plan = plans.findInfo(planId)
                    .orElse(null);

            if (plan == null || plan.status() != RecurringStatus.ACTIVE) {
                return created;
            }

            var period = PeriodCalculator.period(plan.anchorDate(), plan.interval(), plan.nextPeriodIndex());

            if (plan.endDate() != null && period.from().isAfter(plan.endDate())) {
                plans.updateStatus(planId, RecurringStatus.ENDED, today.toString());

                return created;
            }

            if (period.from().isAfter(today.plusDays(plan.leadDays()))) {
                return created;
            }

            var billable = !period.from().isBefore(today.minusDays(properties.catchUpDays()));

            try {
                if (processor.process(plan, period, today, billable) == Outcome.DRAFT_CREATED) {
                    created++;
                }
            } catch (PlanChangedException e) {
                LOG.info("Recurring plan {} changed during the run, retrying on the next run", planId);

                return created;
            } catch (RuleViolationException e) {
                LOG.warn("Recurring plan {} could not generate a draft: {}", planId, e.messageKey());
                plans.updateLastError(planId, e.messageKey());

                return created;
            } catch (RuntimeException e) {
                LOG.error("Recurring plan {} failed", planId, e);
                plans.updateLastError(planId, UNEXPECTED_ERROR);

                return created;
            }
        }
    }
}
