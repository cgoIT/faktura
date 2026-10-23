package de.cgoit.faktura.dunning;

import de.cgoit.faktura.document.InvoiceBalance;
import de.cgoit.faktura.document.PaymentState;

/**
 * A created dunning letter with the invoice it belongs to, for lists.
 *
 * @param balance current payment situation of the invoice, {@code null} if the invoice is unknown
 */
public record DunningRow(DunningInfo dunning, String invoiceNumber, String customerName, InvoiceBalance balance) {

    /**
     * Tells whether the invoice no longer needs attention: it is paid in full or cancelled.
     */
    public boolean settled() {
        return balance != null
                && (balance.state() == PaymentState.PAID || balance.state() == PaymentState.CANCELLED);
    }

    /**
     * Hides the customer name from logs.
     */
    @Override
    public String toString() {
        return "DunningRow[number=" + dunning.number() + "]";
    }
}
