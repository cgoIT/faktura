package de.cgoit.faktura.dunning;

/**
 * Amounts of a dunning letter in cents.
 */
public record DunningAmounts(long openCents, long feeCents, long interestCents) {

    public long totalCents() {
        return openCents + feeCents + interestCents;
    }
}
