package de.cgoit.faktura.recurring;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes billing periods from the anchor date and the interval of a plan. Period n always starts n intervals after
 * the anchor, never one interval after its predecessor, so an anchor on the 31st does not drift to the 28th after a
 * short month. In a shorter month the period starts on the last day of that month. Periods follow each other without
 * gaps: a period ends on the day before the next one starts.
 */
public final class PeriodCalculator {

    private PeriodCalculator() {
    }

    /**
     * Returns the period with the given index.
     */
    public static Period period(LocalDate anchor, RecurringInterval interval, int index) {
        var from = start(anchor, interval, index);
        var to = start(anchor, interval, index + 1)
                .minusDays(1);

        return new Period(index, from, to);
    }

    /**
     * Returns the index of the first period that starts on or after the given day.
     */
    public static int firstIndexOnOrAfter(LocalDate anchor, RecurringInterval interval, LocalDate day) {
        var index = 0;

        if (day.isAfter(anchor)) {
            var months = (day.getYear() - anchor.getYear()) * 12L + day.getMonthValue() - anchor.getMonthValue();

            index = (int) Math.max(0, months / interval.months() - 1);
        }

        while (start(anchor, interval, index).isBefore(day)) {
            index++;
        }

        return index;
    }

    /**
     * Returns the periods starting with the given index, at most {@code count}, none that starts after the end date.
     *
     * @param endDate last day on which a period may start, {@code null} for no end
     */
    public static List<Period> upcoming(LocalDate anchor, RecurringInterval interval, LocalDate endDate, int firstIndex,
            int count) {
        var periods = new ArrayList<Period>();

        for (var index = firstIndex; periods.size() < count; index++) {
            var period = period(anchor, interval, index);

            if (endDate != null && period.from().isAfter(endDate)) {
                break;
            }

            periods.add(period);
        }

        return periods;
    }

    private static LocalDate start(LocalDate anchor, RecurringInterval interval, int index) {
        return anchor.plusMonths((long) index * interval.months());
    }
}
