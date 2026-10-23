package de.cgoit.faktura.recurring;

/**
 * Row of the plan list.
 *
 * @param nextPeriod  next period to generate, {@code null} if the plan has ended
 * @param netCents    net amount of one period (item discounts and total discount applied, before tax)
 * @param customerName name of the customer, empty if the customer is unknown
 */
public record PlanSummary(
        String id,
        String name,
        String customerId,
        String customerName,
        RecurringInterval interval,
        RecurringStatus status,
        Period nextPeriod,
        long netCents,
        String lastError) {

    @Override
    public String toString() {
        return "PlanSummary[id=" + id + "]";
    }
}
