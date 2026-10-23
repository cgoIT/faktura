package de.cgoit.faktura.numbering;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configurable number pattern, e.g. {@code RE{jahr}{#####}} yields RE202600001.
 * Placeholders: {@code {jahr}} (four digits) and exactly one counter {@code {#...}} (number of hashes = minimum width).
 * If the pattern contains {@code {jahr}}, the counter restarts every year.
 */
public final class NumberPattern {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern COUNTER = Pattern.compile("#+");

    private final String pattern;
    private final boolean yearly;
    private final int counterWidth;

    private NumberPattern(String pattern, boolean yearly, int counterWidth) {
        this.pattern = pattern;
        this.yearly = yearly;
        this.counterWidth = counterWidth;
    }

    public static NumberPattern parse(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern must not be blank");
        }
        boolean year = false;
        int counters = 0;
        int width = 0;
        Matcher m = PLACEHOLDER.matcher(pattern);
        int last = 0;
        while (m.find()) {
            checkNoStrayBraces(pattern.substring(last, m.start()));
            last = m.end();
            String name = m.group(1);
            if (name.equals("jahr")) {
                if (year) throw new IllegalArgumentException("{jahr} may occur only once");
                year = true;
            } else if (COUNTER.matcher(name).matches()) {
                counters++;
                width = name.length();
            } else {
                throw new IllegalArgumentException("Unknown placeholder: {" + name + "}");
            }
        }
        checkNoStrayBraces(pattern.substring(last));
        if (counters != 1) {
            throw new IllegalArgumentException("Exactly one counter such as {#####} is required");
        }
        return new NumberPattern(pattern, year, width);
    }

    private static void checkNoStrayBraces(String s) {
        if (s.indexOf('{') >= 0 || s.indexOf('}') >= 0) {
            throw new IllegalArgumentException("Unbalanced curly brace in pattern");
        }
    }

    /** Tells whether the counter restarts every year. */
    public boolean resetsYearly() {
        return yearly;
    }

    public String format(int year, long counter) {
        if (counter < 1) throw new IllegalArgumentException("Counter must be >= 1");
        String c = String.format("%0" + counterWidth + "d", counter);
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(name.equals("jahr") ? String.format("%04d", year) : c));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
