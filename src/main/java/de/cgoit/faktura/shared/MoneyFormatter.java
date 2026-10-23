package de.cgoit.faktura.shared;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Formats amounts given in whole cents for display, e.g. {@code 119000} as {@code 1.190,00 €}.
 * Available in templates as {@code ${@money.format(cents)}}.
 */
@Component("money")
public class MoneyFormatter {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.GERMANY);

    public String format(long cents) {
        var format = new DecimalFormat("#,##0.00", SYMBOLS);

        return format.format(BigDecimal.valueOf(cents, 2)) + " €";
    }

    /**
     * Formats a file size for display, e.g. {@code 48.200} as {@code 47 KB} and {@code 2.500.000} as {@code 2,4 MB}.
     */
    public String fileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }

        return new DecimalFormat("0.0", SYMBOLS).format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    /**
     * Formats basis points as a percentage for display, e.g. {@code 1900} as {@code 19 %}.
     */
    public String percent(int basisPoints) {
        return DecimalInput.formatBasisPoints(basisPoints) + " %";
    }

    /**
     * Formats a quantity in thousandths for display, e.g. {@code 1500} as {@code 1,5}.
     */
    public String quantity(long thousandths) {
        return DecimalInput.formatThousandths(thousandths);
    }
}
