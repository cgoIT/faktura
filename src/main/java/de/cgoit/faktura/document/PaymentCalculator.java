package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Derives the payment state of an invoice. Nothing here is stored: the state follows from the gross total, the
 * payments, the due date and the day of the view.
 */
public final class PaymentCalculator {

    private PaymentCalculator() {
    }

    /**
     * Calculates the balance of a finalized invoice.
     * An invoice is overdue only after the due date: on the due date itself it is still on time. A cancelled
     * invoice and a settled invoice (open amount zero or less, overpayment included) are never overdue.
     *
     * @param paidCents payments including reversing entries
     * @param dueDate   may be {@code null}, then the invoice is never overdue
     * @param cancelled the invoice has a cancellation
     */
    public static InvoiceBalance balance(long grossCents, long paidCents, LocalDate dueDate, boolean cancelled,
            LocalDate today) {
        var open = Math.max(0, grossCents - paidCents);
        var overpaid = Math.max(0, paidCents - grossCents);
        var state = state(grossCents, paidCents, cancelled);
        var overdue = state != PaymentState.CANCELLED
                && state != PaymentState.PAID
                && dueDate != null
                && today.isAfter(dueDate);
        var days = overdue ? ChronoUnit.DAYS.between(dueDate, today) : 0;

        return new InvoiceBalance(state, grossCents, paidCents, open, overpaid, overdue, days);
    }

    private static PaymentState state(long grossCents, long paidCents, boolean cancelled) {
        if (cancelled) {
            return PaymentState.CANCELLED;
        }

        if (paidCents >= grossCents) {
            return PaymentState.PAID;
        }

        return paidCents > 0 ? PaymentState.PARTIALLY_PAID : PaymentState.OPEN;
    }
}
