package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.Map;

import de.cgoit.faktura.render.RenderModel.Account;
import de.cgoit.faktura.render.RenderModel.Party;
import de.cgoit.faktura.textblock.Placeholder;

/**
 * What the dunning module needs to know about a finalized invoice. Sender, recipient and account come from the
 * snapshots of the invoice, so a dunning letter addresses the same parties as the invoice did.
 *
 * @param placeholders escaped values for the invoice placeholders (salutation, customer, invoice number, amounts)
 */
public record DunningBasis(
        String invoiceId,
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        long grossCents,
        String customerNumber,
        Party sender,
        Party recipient,
        Account account,
        Map<Placeholder, String> placeholders) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "DunningBasis[number=" + number + "]";
    }
}
