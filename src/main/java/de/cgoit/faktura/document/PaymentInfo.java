package de.cgoit.faktura.document;

import java.time.LocalDate;

/**
 * A recorded payment. A reversing entry has a negative amount and points to the payment it cancels.
 *
 * @param reversesPaymentId the corrected payment, for a reversing entry
 * @param reversedById      the reversing entry, for a payment that was corrected
 */
public record PaymentInfo(
        String id,
        String documentId,
        LocalDate date,
        long amountCents,
        PaymentMethod method,
        String reference,
        String note,
        String reversesPaymentId,
        String reversedById) {

    public boolean isReversal() {
        return reversesPaymentId != null;
    }

    /**
     * Tells whether the payment can still be corrected: only a payment that is neither a reversal nor reversed.
     */
    public boolean canBeReversed() {
        return reversesPaymentId == null && reversedById == null;
    }

    /**
     * Hides the free texts from logs.
     */
    @Override
    public String toString() {
        return "PaymentInfo[id=" + id + "]";
    }
}
