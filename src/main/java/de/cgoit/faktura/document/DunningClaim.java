package de.cgoit.faktura.document;

/**
 * Costs a dunning letter claims on top of the invoice. They are only shown next to the payments: the balance and the
 * payment state of the invoice ignore them.
 *
 * @param number    number of the letter
 * @param costCents fee plus interest of the letter
 */
public record DunningClaim(String number, long feeCents, long interestCents) {

    public long costCents() {
        return feeCents + interestCents;
    }

    /**
     * Returns what is still unpaid of the invoice and the costs of the letter together.
     */
    public long openCents(InvoiceBalance balance) {
        return Math.max(0, balance.grossCents() + costCents() - balance.paidCents());
    }

    /**
     * Returns the part of the payments above the gross total that goes to the costs.
     */
    public long paidCostCents(InvoiceBalance balance) {
        return Math.min(balance.overpaidCents(), costCents());
    }

    /**
     * Returns what was paid beyond the invoice and the costs.
     */
    public long overpaidCents(InvoiceBalance balance) {
        return balance.overpaidCents() - paidCostCents(balance);
    }
}
