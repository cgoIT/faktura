package de.cgoit.faktura.shared;

/**
 * Display format of an IBAN: groups of four characters separated by blanks, e.g. {@code DE02 1203 0000 0000 2020 51}.
 * The stored and encoded form stays compact.
 */
public final class Iban {

    private static final int GROUP_SIZE = 4;

    private Iban() {
    }

    /**
     * Groups an IBAN for display; blanks in the input are ignored. {@code null} and blank input give {@code null}.
     */
    public static String format(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }

        var compact = iban.replaceAll("\\s", "");
        var grouped = new StringBuilder();

        for (var i = 0; i < compact.length(); i += GROUP_SIZE) {
            if (i > 0) {
                grouped.append(' ');
            }

            grouped.append(compact, i, Math.min(i + GROUP_SIZE, compact.length()));
        }

        return grouped.toString();
    }
}
