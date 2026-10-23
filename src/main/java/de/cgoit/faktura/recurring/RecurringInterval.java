package de.cgoit.faktura.recurring;

/**
 * How often a plan bills. A period always lasts this many months, counted from the anchor date of the plan.
 */
public enum RecurringInterval {
    MONTH(1), TWO_MONTHS(2), QUARTER(3), HALF_YEAR(6), YEAR(12);

    private final int months;

    RecurringInterval(int months) {
        this.months = months;
    }

    /**
     * Returns the length of one period in months.
     */
    public int months() {
        return months;
    }
}
