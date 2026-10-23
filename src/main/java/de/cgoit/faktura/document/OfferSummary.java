package de.cgoit.faktura.document;

import java.time.LocalDate;

/**
 * Row of the offer list.
 *
 * @param number       {@code null} for drafts
 * @param state        derived state of the offer, see {@link OfferState}
 * @param customerName display name of the customer, empty if the customer is unknown
 * @param validUntil   last day the offer is valid
 */
public record OfferSummary(
        String id,
        DocumentStatus status,
        OfferState state,
        String number,
        String customerId,
        String customerName,
        String subject,
        LocalDate issueDate,
        LocalDate validUntil,
        long grossTotalCents) {

    /**
     * Hides the customer name from logs.
     */
    @Override
    public String toString() {
        return "OfferSummary[id=" + id + "]";
    }
}
