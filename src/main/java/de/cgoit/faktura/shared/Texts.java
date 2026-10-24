package de.cgoit.faktura.shared;

import java.util.Locale;

/**
 * Small helpers for text values of inputs and searches.
 */
public final class Texts {

    private Texts() {
    }

    /**
     * Tells whether the text is {@code null} or blank.
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Returns the stripped text, or {@code null} if it is {@code null} or blank.
     */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Tells whether the value contains the search text, ignoring case by German rules.
     *
     * @param needle search text, already in lower case
     */
    public static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.GERMAN).contains(needle);
    }
}
