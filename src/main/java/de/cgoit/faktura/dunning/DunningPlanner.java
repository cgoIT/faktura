package de.cgoit.faktura.dunning;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.shared.Rounding;

/**
 * Pure rules of the dunning process: which level is suggested on a day and what a letter costs. Nothing here reads
 * the clock or the database, so every rule is testable with plain values.
 */
public final class DunningPlanner {

    private static final BigInteger DAYS_PER_YEAR = BigInteger.valueOf(365);

    private DunningPlanner() {
    }

    /**
     * Returns the level to suggest for an overdue invoice. It is the lowest active level above the highest level
     * already issued whose threshold ({@code daysAfterDue}, counted from the due date of the invoice) has been
     * reached. If a letter was issued before, its new due date must have passed as well, so the customer always gets
     * the full term. Levels are never skipped in a suggestion.
     *
     * @param daysOverdue days since the due date of the invoice
     * @param issued      letters already created for the invoice
     */
    public static Optional<DunningLevelInfo> suggest(List<DunningLevelInfo> levels, List<DunningInfo> issued,
            long daysOverdue, LocalDate today) {
        var highest = issued.stream()
                .mapToInt(DunningInfo::level)
                .max()
                .orElse(0);
        var termRunning = issued.stream()
                .anyMatch(dunning -> !today.isAfter(dunning.newDueDate()));

        if (termRunning) {
            return Optional.empty();
        }

        return levels.stream()
                .filter(DunningLevelInfo::active)
                .filter(level -> level.level() > highest)
                .sorted(Comparator.comparingInt(DunningLevelInfo::level))
                .findFirst()
                .filter(level -> daysOverdue >= level.daysAfterDue());
    }

    /**
     * Calculates fee and interest of a letter. The fee is the fixed fee of the level. Interest is the annual rate on
     * the open amount for the days since the due date (365-day year), rounded half up; both are zero when the level
     * has none.
     */
    public static DunningAmounts amounts(DunningLevelInfo level, long openCents, long daysOverdue) {
        var interest = 0L;

        if (level.interestBp() > 0 && daysOverdue > 0 && openCents > 0) {
            var numerator = BigInteger.valueOf(openCents)
                    .multiply(BigInteger.valueOf(level.interestBp()))
                    .multiply(BigInteger.valueOf(daysOverdue));
            var denominator = BigInteger.valueOf(10_000)
                    .multiply(DAYS_PER_YEAR);

            interest = Rounding.divideHalfUp(numerator, denominator);
        }

        return new DunningAmounts(openCents, level.feeCents(), interest);
    }
}
