package de.cgoit.faktura.dunning;

import java.time.LocalDate;

/**
 * A dunning letter that was created. It never changes afterwards.
 */
public record DunningInfo(
        String id,
        String invoiceId,
        int level,
        String levelName,
        String number,
        LocalDate date,
        long openCents,
        long feeCents,
        long interestCents,
        LocalDate newDueDate) {

    public long totalCents() {
        return openCents + feeCents + interestCents;
    }
}
