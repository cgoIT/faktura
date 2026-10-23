package de.cgoit.faktura.recurring;

import java.time.LocalDate;

/**
 * A generated draft waiting for the user's decision.
 *
 * @param documentId the draft invoice
 * @param grossCents gross total of the draft as last calculated
 * @param createdOn  day the draft was generated
 */
public record ReleaseEntry(
        String runId,
        String planId,
        String planName,
        String customerName,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        String documentId,
        long grossCents,
        LocalDate createdOn,
        RecurringInterval interval,
        LocalDate anchorDate) {

    @Override
    public String toString() {
        return "ReleaseEntry[runId=" + runId + "]";
    }
}
