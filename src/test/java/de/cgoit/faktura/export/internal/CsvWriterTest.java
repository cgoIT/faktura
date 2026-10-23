package de.cgoit.faktura.export.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvWriterTest {

    @ParameterizedTest
    @CsvSource({
            "0, '0,00'",
            "5, '0,05'",
            "99, '0,99'",
            "100, '1,00'",
            "-1, '-0,01'",
            "-12345, '-123,45'",
            "123456789, '1234567,89'",
            "9223372036854775807, '92233720368547758,07'",
            "-9223372036854775808, '-92233720368547758,08'" })
    void amountUsesDecimalCommaWithoutSeparators(long cents, String expected) {
        assertThat(CsvWriter.amount(cents)).isEqualTo(expected);
    }

    @Test
    void plainTextStaysUnquoted() {
        assertThat(CsvWriter.text("Muster GmbH")).isEqualTo("Muster GmbH");
        assertThat(CsvWriter.text(null)).isEmpty();
        assertThat(CsvWriter.text("")).isEmpty();
    }

    @Test
    void separatorQuoteAndLineBreakAreQuoted() {
        assertThat(CsvWriter.text("a;b")).isEqualTo("\"a;b\"");
        assertThat(CsvWriter.text("sagt \"hallo\"")).isEqualTo("\"sagt \"\"hallo\"\"\"");
        assertThat(CsvWriter.text("zwei\nZeilen")).isEqualTo("\"zwei\nZeilen\"");
        assertThat(CsvWriter.text("zwei\r\nZeilen")).isEqualTo("\"zwei\r\nZeilen\"");
    }

    @ParameterizedTest
    @ValueSource(strings = { "=1+1", "+49 170", "-5", "@SUM(A1)", "\tTab", "\rCR" })
    void formulaStartsGetAnApostrophe(String value) {
        assertThat(CsvWriter.text(value)).contains("'" + value.charAt(0)).doesNotStartWith("=");
    }

    @Test
    void formulaStartWithSeparatorIsDefusedAndQuoted() {
        assertThat(CsvWriter.text("=HYPERLINK(\"x\";\"y\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\";\"\"y\"\")\"");
    }

    @Test
    void textInsideACellIsNotTouched() {
        assertThat(CsvWriter.text("a=b")).isEqualTo("a=b");
        assertThat(CsvWriter.text("Müller-Lüdenscheidt")).isEqualTo("Müller-Lüdenscheidt");
    }

    @Test
    void dateIsIsoAndEmptyWhenMissing() {
        assertThat(CsvWriter.date(LocalDate.of(2027, 1, 5))).isEqualTo("2027-01-05");
        assertThat(CsvWriter.date(null)).isEmpty();
    }

    @Test
    void rowEndsWithCrLf() {
        assertThat(CsvWriter.row(List.of("a", "", "c"))).isEqualTo("a;;c\r\n");
    }
}
