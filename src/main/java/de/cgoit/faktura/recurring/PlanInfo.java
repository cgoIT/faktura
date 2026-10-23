package de.cgoit.faktura.recurring;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.document.TotalDiscount;

/**
 * A stored plan with its item templates.
 *
 * @param nextPeriodIndex index of the next period the daily job will consider
 * @param lastError       message key of the reason the last run failed for this plan, {@code null} if it did not
 * @param version         number of saves by a person; the editor sends it back so a parallel save is detected
 * @param customerReference default customer reference of generated invoices, {@code null} for none
 */
public record PlanInfo(
        String id,
        String customerId,
        String name,
        RecurringInterval interval,
        LocalDate anchorDate,
        LocalDate endDate,
        int leadDays,
        RecurringStatus status,
        int nextPeriodIndex,
        Integer paymentDays,
        String subject,
        String headerText,
        String footerText,
        TotalDiscount totalDiscount,
        String contactId,
        String lastError,
        List<PlanItemInput> items,
        long version,
        String customerReference) {

    /**
     * Returns the next period the job will consider, {@code null} if the plan has ended.
     */
    public Period nextPeriod() {
        var period = PeriodCalculator.period(anchorDate, interval, nextPeriodIndex);

        return endDate != null && period.from().isAfter(endDate) ? null : period;
    }

    /**
     * Shows only the identifier: name and texts must not end up in logs.
     */
    @Override
    public String toString() {
        return "PlanInfo[id=" + id + "]";
    }
}
