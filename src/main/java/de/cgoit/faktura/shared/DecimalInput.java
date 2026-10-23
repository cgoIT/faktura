package de.cgoit.faktura.shared;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Parses and formats decimal user input in German notation without floating point. Money becomes whole cents,
 * percentages become basis points (1900 = 19 %).
 */
public final class DecimalInput {

    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d+)?");
    private static final Pattern PLAIN_COMMA = Pattern.compile("\\d+(,\\d+)?");
    private static final Pattern PLAIN_DOT = Pattern.compile("\\d+(\\.\\d{1,3})?");

    private static final long MAX_THOUSANDTHS = 999_999_999L;

    private DecimalInput() {
    }

    /**
     * Parses an amount such as {@code 85}, {@code 85,5}, {@code 1.234,50} or {@code 85.50} into whole cents.
     *
     * @throws RuleViolationException with key {@code validation.money} if the text is not a non-negative amount
     *                                with at most two decimals
     */
    public static long parseCents(String text) {
        return parseScaled(text, 2, "validation.money");
    }

    /**
     * Parses a percentage such as {@code 5}, {@code 5,5} or {@code 19} into basis points, allowing 0 to 100 %.
     *
     * @throws RuleViolationException with key {@code validation.percent} if the text is invalid or out of range
     */
    public static int parseBasisPoints(String text) {
        var value = parseScaled(text, 2, "validation.percent");

        if (value > 10_000) {
            throw new RuleViolationException("validation.percent");
        }

        return (int) value;
    }

    /**
     * Parses a quantity such as {@code 6}, {@code 1,5} or {@code 0,25} into thousandths (1,5 = 1500) and requires it
     * to be greater than zero.
     *
     * @throws RuleViolationException with key {@code validation.quantity} if the text is not a positive number with
     *                                at most three decimals
     */
    public static long parseThousandths(String text) {
        var value = parseScaled(dotAsDecimalSeparator(text), 3, "validation.quantity");

        if (value <= 0 || value > MAX_THOUSANDTHS) {
            throw new RuleViolationException("validation.quantity");
        }

        return value;
    }

    /**
     * Formats cents as an input value without currency, e.g. {@code 8500} as {@code 85,00}.
     */
    public static String formatCents(long cents) {
        return plain(BigDecimal.valueOf(cents, 2), 2);
    }

    /**
     * Formats thousandths as a quantity without trailing zeros, e.g. {@code 1500} as {@code 1,5}.
     */
    public static String formatThousandths(long thousandths) {
        return plain(BigDecimal.valueOf(thousandths, 3).stripTrailingZeros(), 0);
    }

    /**
     * Formats basis points as a percentage without trailing zeros, e.g. {@code 550} as {@code 5,5}.
     */
    public static String formatBasisPoints(int basisPoints) {
        return plain(BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros(), 0);
    }

    /**
     * Quantities never use dots for grouping: {@code 1.500} means 1,5, not 1500, unless a comma is present.
     */
    private static String dotAsDecimalSeparator(String text) {
        return text != null && text.contains(".") && !text.contains(",") ? text.replace('.', ',') : text;
    }

    private static String plain(BigDecimal value, int minScale) {
        var scaled = value.scale() < minScale ? value.setScale(minScale) : value;

        return scaled.toPlainString()
                .replace('.', ',');
    }

    private static long parseScaled(String text, int scale, String errorKey) {
        var cleaned = text == null ? "" : text.replace("€", "")
                .replace("%", "");
        var compact = cleaned.replaceAll("\\s", "");
        String normalized;

        if (GROUPED.matcher(compact).matches()) {
            normalized = compact.replace(".", "")
                    .replace(',', '.');
        } else if (PLAIN_COMMA.matcher(compact).matches()) {
            normalized = compact.replace(',', '.');
        } else if (PLAIN_DOT.matcher(compact).matches()) {
            normalized = compact;
        } else {
            throw new RuleViolationException(errorKey);
        }

        var decimal = new BigDecimal(normalized).stripTrailingZeros();

        if (decimal.scale() > scale) {
            throw new RuleViolationException(errorKey);
        }

        try {
            return decimal.movePointRight(scale).longValueExact();
        } catch (ArithmeticException e) {
            throw new RuleViolationException(errorKey);
        }
    }
}
