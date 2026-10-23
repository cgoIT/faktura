package de.cgoit.faktura.dunning;

import java.time.LocalDate;

/**
 * What the user sees before a dunning letter is created: the invoice, the level and the amounts as they would be
 * today.
 */
public record DunningReview(
        String invoiceId,
        String invoiceNumber,
        String customerName,
        LocalDate invoiceDate,
        LocalDate dueDate,
        long daysOverdue,
        long invoiceGrossCents,
        DunningLevelInfo level,
        DunningAmounts amounts,
        LocalDate newDueDate) {

    /**
     * Hides the customer name from logs.
     */
    @Override
    public String toString() {
        return "DunningReview[invoiceNumber=" + invoiceNumber + "]";
    }
}
