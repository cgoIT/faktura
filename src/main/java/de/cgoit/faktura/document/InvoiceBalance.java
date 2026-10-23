package de.cgoit.faktura.document;

/**
 * Derived payment situation of an invoice at a given day.
 *
 * @param openCents     gross total minus payments, never negative
 * @param overpaidCents payments above the gross total, otherwise 0
 * @param overdue       the invoice is not settled and the due date is before the day
 * @param daysOverdue   days after the due date, 0 if not overdue
 */
public record InvoiceBalance(
        PaymentState state,
        long grossCents,
        long paidCents,
        long openCents,
        long overpaidCents,
        boolean overdue,
        long daysOverdue) {
}
