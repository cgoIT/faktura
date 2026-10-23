package de.cgoit.faktura.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PeriodCalculatorTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static Stream<Arguments> periods() {
        return Stream.of(
                // anchor, interval, index, expected from, expected to
                Arguments.of("2026-01-01", RecurringInterval.MONTH, 0, "2026-01-01", "2026-01-31"),
                Arguments.of("2026-01-01", RecurringInterval.MONTH, 1, "2026-02-01", "2026-02-28"),
                Arguments.of("2027-12-01", RecurringInterval.MONTH, 2, "2028-02-01", "2028-02-29"),
                Arguments.of("2026-01-01", RecurringInterval.QUARTER, 0, "2026-01-01", "2026-03-31"),
                Arguments.of("2026-01-01", RecurringInterval.QUARTER, 3, "2026-10-01", "2026-12-31"),
                Arguments.of("2026-02-15", RecurringInterval.QUARTER, 0, "2026-02-15", "2026-05-14"),
                Arguments.of("2026-02-15", RecurringInterval.QUARTER, 1, "2026-05-15", "2026-08-14"),
                Arguments.of("2026-02-15", RecurringInterval.QUARTER, 2, "2026-08-15", "2026-11-14"),
                Arguments.of("2026-10-01", RecurringInterval.TWO_MONTHS, 0, "2026-10-01", "2026-11-30"),
                Arguments.of("2026-10-01", RecurringInterval.TWO_MONTHS, 1, "2026-12-01", "2027-01-31"),
                Arguments.of("2026-12-31", RecurringInterval.TWO_MONTHS, 1, "2027-02-28", "2027-04-29"),
                Arguments.of("2026-10-01", RecurringInterval.HALF_YEAR, 0, "2026-10-01", "2027-03-31"),
                Arguments.of("2026-10-01", RecurringInterval.HALF_YEAR, 1, "2027-04-01", "2027-09-30"),
                Arguments.of("2026-08-31", RecurringInterval.HALF_YEAR, 1, "2027-02-28", "2027-08-30"),
                Arguments.of("2026-03-15", RecurringInterval.YEAR, 0, "2026-03-15", "2027-03-14"),
                Arguments.of("2026-03-15", RecurringInterval.YEAR, 1, "2027-03-15", "2028-03-14"),
                // leap day anchor: year interval clamps to the last day of February
                Arguments.of("2024-02-29", RecurringInterval.YEAR, 0, "2024-02-29", "2025-02-27"),
                Arguments.of("2024-02-29", RecurringInterval.YEAR, 1, "2025-02-28", "2026-02-27"),
                Arguments.of("2024-02-29", RecurringInterval.YEAR, 4, "2028-02-29", "2029-02-27"),
                // anchor on the 31st: short months use their last day, the anchor does not drift
                Arguments.of("2026-01-31", RecurringInterval.MONTH, 1, "2026-02-28", "2026-03-30"),
                Arguments.of("2026-01-31", RecurringInterval.MONTH, 2, "2026-03-31", "2026-04-29"),
                Arguments.of("2026-01-31", RecurringInterval.MONTH, 3, "2026-04-30", "2026-05-30"),
                Arguments.of("2026-01-31", RecurringInterval.MONTH, 4, "2026-05-31", "2026-06-29"),
                Arguments.of("2028-01-31", RecurringInterval.MONTH, 1, "2028-02-29", "2028-03-30"),
                // anchor on the 30th and the 29th
                Arguments.of("2026-01-30", RecurringInterval.MONTH, 1, "2026-02-28", "2026-03-29"),
                Arguments.of("2026-01-30", RecurringInterval.MONTH, 2, "2026-03-30", "2026-04-29"),
                Arguments.of("2026-01-29", RecurringInterval.MONTH, 1, "2026-02-28", "2026-03-28"),
                Arguments.of("2028-01-29", RecurringInterval.MONTH, 1, "2028-02-29", "2028-03-28"),
                // quarter with anchor on the 31st
                Arguments.of("2026-08-31", RecurringInterval.QUARTER, 1, "2026-11-30", "2027-02-27"),
                Arguments.of("2026-08-31", RecurringInterval.QUARTER, 2, "2027-02-28", "2027-05-30"),
                Arguments.of("2026-08-31", RecurringInterval.QUARTER, 3, "2027-05-31", "2027-08-30"));
    }

    @ParameterizedTest(name = "{0} {1} #{2} = {3}..{4}")
    @MethodSource("periods")
    void computesPeriodFromAnchorAndIndex(String anchor, RecurringInterval interval, int index, String from,
            String to) {
        var period = PeriodCalculator.period(d(anchor), interval, index);

        assertThat(period.from()).isEqualTo(d(from));
        assertThat(period.to()).isEqualTo(d(to));
        assertThat(period.index()).isEqualTo(index);
    }

    @ParameterizedTest
    @MethodSource("anchors")
    void periodsFollowEachOtherWithoutGapsOrOverlap(String anchor, RecurringInterval interval) {
        for (var index = 0; index < 60; index++) {
            var current = PeriodCalculator.period(d(anchor), interval, index);
            var next = PeriodCalculator.period(d(anchor), interval, index + 1);

            assertThat(current.to()).isBeforeOrEqualTo(next.from()
                    .minusDays(1))
                    .isEqualTo(next.from()
                            .minusDays(1));
            assertThat(current.from()).isBeforeOrEqualTo(current.to());
        }
    }

    static Stream<Arguments> anchors() {
        return Stream.of("2026-01-31", "2024-02-29", "2026-02-15", "2026-11-30", "2026-12-01")
                .flatMap(anchor -> Stream.of(RecurringInterval.values())
                        .map(interval -> Arguments.of(anchor, interval)));
    }

    @Test
    void firstIndexOnOrAfterFindsTheFirstPeriodStartingOnOrAfterTheDay() {
        var anchor = d("2026-01-31");

        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.MONTH, d("2025-12-01"))).isZero();
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.MONTH, d("2026-01-31"))).isZero();
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.MONTH, d("2026-02-01"))).isEqualTo(1);
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.MONTH, d("2026-02-28"))).isEqualTo(1);
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.MONTH, d("2026-03-01"))).isEqualTo(2);
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.QUARTER, d("2026-05-01"))).isEqualTo(2);
        assertThat(PeriodCalculator.firstIndexOnOrAfter(anchor, RecurringInterval.YEAR, d("2030-06-01"))).isEqualTo(5);
    }

    @Test
    void firstIndexMatchesBruteForceForManyDays() {
        var anchor = d("2026-01-31");

        for (var interval : RecurringInterval.values()) {
            for (var day = d("2025-11-01"); day.isBefore(d("2029-03-01")); day = day.plusDays(7)) {
                var index = PeriodCalculator.firstIndexOnOrAfter(anchor, interval, day);

                assertThat(PeriodCalculator.period(anchor, interval, index)
                        .from()).isAfterOrEqualTo(day);

                if (index > 0) {
                    assertThat(PeriodCalculator.period(anchor, interval, index - 1)
                            .from()).isBefore(day);
                }
            }
        }
    }

    @Test
    void upcomingStopsAtTheEndDate() {
        var periods = PeriodCalculator.upcoming(d("2026-11-01"), RecurringInterval.QUARTER, d("2027-05-01"), 0, 10);

        assertThat(periods).extracting(Period::from)
                .containsExactly(d("2026-11-01"), d("2027-02-01"), d("2027-05-01"));
    }

    @Test
    void upcomingReturnsTheRequestedCountWithoutEndDate() {
        var periods = PeriodCalculator.upcoming(d("2026-11-01"), RecurringInterval.MONTH, null, 2, 4);

        assertThat(periods).extracting(Period::index)
                .containsExactly(2, 3, 4, 5);
    }
}
