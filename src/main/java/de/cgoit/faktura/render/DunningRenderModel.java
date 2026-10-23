package de.cgoit.faktura.render;

import java.time.LocalDate;

import de.cgoit.faktura.render.RenderModel.Account;
import de.cgoit.faktura.render.RenderModel.Party;

/**
 * Everything a dunning letter prints. The renderer calculates nothing: amounts and the new due date are final.
 *
 * @param draft            preview without number; the letter carries a watermark
 * @param number           dunning number, {@code null} for a preview
 * @param date             date of the letter
 * @param levelName        name of the dunning level, printed as the title
 * @param invoiceGrossCents total of the invoice
 * @param paidCents        payments received so far
 * @param openCents        outstanding amount of the invoice
 * @param feeCents         dunning fee, 0 if none
 * @param interestCents    default interest, 0 if none
 * @param totalCents       open amount plus fee plus interest
 * @param newDueDate       date by which the total is due
 */
public record DunningRenderModel(
        boolean draft,
        String number,
        LocalDate date,
        String levelName,
        Party sender,
        Party recipient,
        String customerNumber,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate invoiceDueDate,
        long invoiceGrossCents,
        long paidCents,
        long openCents,
        long feeCents,
        long interestCents,
        long totalCents,
        LocalDate newDueDate,
        String headerText,
        String footerText,
        Account account) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "DunningRenderModel[number=" + number + "]";
    }
}
