package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalInputTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "85|8500", "85,5|8550", "85,50|8550", "1.234,50|123450", "1.234|123400", "85.50|8550", "0|0",
            "0,01|1", " 12,00 € |1200", "1.234.567,89|123456789"})
    void parsesGermanAmountsToCents(String text, long cents) {
        assertThat(DecimalInput.parseCents(text)).isEqualTo(cents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "-5", "1,234", "1,2,3", "1..234", "12,", ",5", "1e3", "99999999999999999999"})
    void rejectsInvalidAmounts(String text) {
        assertThatThrownBy(() -> DecimalInput.parseCents(text)).isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"5|500", "5,5|550", "19|1900", "0|0", "100|10000", "7,25 %|725"})
    void parsesPercentToBasisPoints(String text, int basisPoints) {
        assertThat(DecimalInput.parseBasisPoints(text)).isEqualTo(basisPoints);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100,01", "101", "-1", "x", "5,555", "99999999999999999999"})
    void rejectsInvalidPercentages(String text) {
        assertThatThrownBy(() -> DecimalInput.parseBasisPoints(text)).isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"8500|85,00", "5|0,05", "0|0,00", "123456|1234,56"})
    void formatsCentsForInput(long cents, String text) {
        assertThat(DecimalInput.formatCents(cents)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"550|5,5", "1900|19", "0|0", "725|7,25"})
    void formatsBasisPointsForInput(int basisPoints, String text) {
        assertThat(DecimalInput.formatBasisPoints(basisPoints)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"6|6000", "1,5|1500", "0,25|250", "0,001|1", "1.5|1500", "1.500|1500", "1.234,567|1234567"})
    void parsesQuantityToThousandths(String text, long thousandths) {
        assertThat(DecimalInput.parseThousandths(text)).isEqualTo(thousandths);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0,000", "-1", "1,2345", "x", "99999999999"})
    void rejectsInvalidQuantities(String text) {
        assertThatThrownBy(() -> DecimalInput.parseThousandths(text)).isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"6000|6", "1500|1,5", "250|0,25", "1|0,001"})
    void formatsThousandthsForInput(long thousandths, String text) {
        assertThat(DecimalInput.formatThousandths(thousandths)).isEqualTo(text);
    }
}
