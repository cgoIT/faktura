package de.cgoit.faktura.dunning;

import java.time.LocalDate;

/**
 * An overdue invoice for which a dunning letter is suggested on the day of the view.
 */
public record DunningSuggestion(
        String invoiceId,
        String invoiceNumber,
        String customerName,
        LocalDate dueDate,
        long daysOverdue,
        DunningLevelInfo level,
        DunningAmounts amounts) {

    /**
     * Hides the customer name from logs.
     */
    @Override
    public String toString() {
        return "DunningSuggestion[invoiceNumber=" + invoiceNumber + "]";
    }
}
