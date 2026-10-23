package de.cgoit.faktura.export.internal;

import java.time.LocalDate;
import java.util.List;

/**
 * Formats cells and rows of the accounting CSV files: separator semicolon, decimal comma, ISO dates, CRLF line ends.
 * Text cells are quoted where needed and defused against spreadsheet formulas.
 */
public final class CsvWriter {

    /**
     * Byte order mark so that spreadsheet programs read the file as UTF-8.
     */
    public static final String BOM = "\uFEFF";

    private static final String LINE_END = "\r\n";

    private CsvWriter() {
    }

    /**
     * Formats a text cell. A text that a spreadsheet would read as a formula (starting with {@code =}, {@code +},
     * {@code -}, {@code @}, tab or carriage return) gets a leading apostrophe. A text with separator, quote or line
     * break is quoted, with quotes doubled. {@code null} gives an empty cell.
     */
    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        var safe = isFormulaStart(value.charAt(0)) ? "'" + value : value;

        if (safe.indexOf(';') < 0 && safe.indexOf('"') < 0 && safe.indexOf('\n') < 0 && safe.indexOf('\r') < 0) {
            return safe;
        }

        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    /**
     * Formats cents as a decimal number with a comma and two digits, without thousands separator, e.g.
     * {@code -1234} as {@code -12,34}.
     */
    public static String amount(long cents) {
        var negative = cents < 0;
        var digits = Long.toString(cents);

        if (negative) {
            digits = digits.substring(1);
        }

        digits = "0".repeat(Math.max(0, 3 - digits.length())) + digits;

        var split = digits.length() - 2;

        return (negative ? "-" : "") + digits.substring(0, split) + "," + digits.substring(split);
    }

    /**
     * Formats a date as ISO text, empty for {@code null}.
     */
    public static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * Joins cells, already formatted, to a line including the line end.
     */
    public static String row(List<String> cells) {
        return String.join(";", cells) + LINE_END;
    }

    private static boolean isFormulaStart(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
