package de.cgoit.faktura.dunning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DunningPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 3, 1);

    private static final List<DunningLevelInfo> LEVELS = List.of(
            level(1, "Zahlungserinnerung", 7, 0, 0, true),
            level(2, "1. Mahnung", 21, 500, 0, true),
            level(3, "2. Mahnung", 35, 1000, 500, true));

    private static DunningLevelInfo level(int number, String name, int days, long fee, int interestBp, boolean active) {
        return new DunningLevelInfo("id" + number, number, name, days, fee, interestBp, 7, "Kopf", "Fuß", active);
    }

    private static DunningInfo issued(int level, LocalDate newDueDate) {
        return new DunningInfo("d" + level, "inv", level, "Stufe " + level, "MA" + level, TODAY.minusDays(20), 10_000, 0, 0,
                newDueDate);
    }

    @ParameterizedTest(name = "{0} days overdue, nothing issued: level {1}")
    @CsvSource({
            "0,  none",
            "6,  none",
            "7,  1",
            "20, 1",
            "21, 1",
            "60, 1"
    })
    void firstLevelIsSuggestedFromItsThreshold(long daysOverdue, String expected) {
        var suggestion = DunningPlanner.suggest(LEVELS, List.of(), daysOverdue, TODAY);

        assertThat(suggestion.map(DunningLevelInfo::level)
                .map(String::valueOf)
                .orElse("none")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "level 1 issued, term ends {0}, {1} days overdue: level {2}")
    @CsvSource({
            "2027-03-01, 20, none",
            "2027-03-02, 30, none",
            "2027-02-28, 20, none",
            "2027-02-28, 21, 2",
            "2027-02-28, 40, 2"
    })
    void nextLevelWaitsForTheTermOfTheLastLetterAndItsThreshold(LocalDate termEnds, long daysOverdue, String expected) {
        var suggestion = DunningPlanner.suggest(LEVELS, List.of(issued(1, termEnds)), daysOverdue, TODAY);

        assertThat(suggestion.map(DunningLevelInfo::level)
                .map(String::valueOf)
                .orElse("none")).isEqualTo(expected);
    }

    @Test
    void lastLevelIssuedMeansNoFurtherSuggestion() {
        var all = List.of(issued(1, TODAY.minusDays(30)), issued(2, TODAY.minusDays(20)), issued(3, TODAY.minusDays(10)));

        assertThat(DunningPlanner.suggest(LEVELS, all, 90, TODAY)).isEmpty();
    }

    @Test
    void inactiveLevelsAreSkipped() {
        var levels = List.of(level(1, "A", 7, 0, 0, false), level(2, "B", 21, 0, 0, true));

        assertThat(DunningPlanner.suggest(levels, List.of(), 10, TODAY)).isEmpty();
        assertThat(DunningPlanner.suggest(levels, List.of(), 21, TODAY))
                .map(DunningLevelInfo::level)
                .contains(2);
    }

    @Test
    void feeIsTheFixedFeeAndOptional() {
        assertThat(DunningPlanner.amounts(LEVELS.get(1), 10_000, 30).feeCents()).isEqualTo(500);
        assertThat(DunningPlanner.amounts(LEVELS.get(0), 10_000, 30).feeCents()).isZero();
    }

    @ParameterizedTest(name = "{0} open, {1} bp, {2} days: interest {3}")
    @CsvSource({
            "10000, 500, 365, 500",
            "10000, 500, 36,  49",
            "10000, 500, 37,  51",
            "10000, 0,   100, 0",
            "10000, 500, 0,   0",
            "1,     500, 1,   0",
            "123456, 900, 45, 1370"
    })
    void interestIsAnnualRateForTheDaysOverdueRoundedHalfUp(long open, int bp, long days, long expected) {
        var level = level(3, "2. Mahnung", 35, 0, bp, true);

        assertThat(DunningPlanner.amounts(level, open, days).interestCents()).isEqualTo(expected);
    }

    @Test
    void totalAddsOpenFeeAndInterest() {
        var amounts = DunningPlanner.amounts(level(3, "2. Mahnung", 35, 1000, 500, true), 10_000, 73);

        assertThat(amounts.interestCents()).isEqualTo(100);
        assertThat(amounts.totalCents()).isEqualTo(11_100);
    }
}
