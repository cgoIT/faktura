package de.cgoit.faktura.dunning;

/**
 * A configured dunning level.
 *
 * @param level        position in the sequence, 1 is the mildest
 * @param daysAfterDue days after the due date of the invoice from which the level is suggested
 * @param feeCents     dunning fee in cents, 0 for none
 * @param interestBp   annual default interest in basis points on the open amount, 0 for none
 * @param newDueDays   days from the date of the letter until the new due date
 * @param headerText   text above the claim, with placeholders
 * @param footerText   text below the claim, with placeholders
 */
public record DunningLevelInfo(
        String id,
        int level,
        String name,
        int daysAfterDue,
        long feeCents,
        int interestBp,
        int newDueDays,
        String headerText,
        String footerText,
        boolean active) {
}
