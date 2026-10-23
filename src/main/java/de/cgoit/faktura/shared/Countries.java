package de.cgoit.faktura.shared;

import static java.util.stream.Collectors.toUnmodifiableSet;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The countries for selection lists: ISO 3166 alpha-2 codes from the Java runtime (plus {@code XK} for Kosovo) with
 * their German names, sorted by name. The database stores only the code.
 */
public final class Countries {

    /** Codes outside ISO 3166 that are in common use in Europe: {@code XK} is Kosovo. */
    private static final List<String> EXTRA_CODES = List.of("XK");

    private static final List<Country> ALL = load();

    private static final Set<String> CODES = ALL.stream()
            .map(Country::code)
            .collect(toUnmodifiableSet());

    private Countries() {
    }

    /**
     * A country as shown in a selection list.
     *
     * @param code ISO 3166 alpha-2 code, e.g. {@code DE}
     * @param name German name, e.g. {@code Deutschland}
     */
    public record Country(String code, String name) {
    }

    /**
     * Returns all countries sorted by German name.
     */
    public static List<Country> all() {
        return ALL;
    }

    /**
     * Tells whether the code is an ISO 3166 alpha-2 code or one of the extra codes (XK). The check is case-sensitive: callers normalize first.
     */
    public static boolean isKnown(String code) {
        return code != null && CODES.contains(code);
    }

    private static List<Country> load() {
        var collator = Collator.getInstance(Locale.GERMAN);

        return Stream.concat(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).stream(),
                        EXTRA_CODES.stream())
                .map(code -> new Country(code, new Locale.Builder().setRegion(code).build()
                        .getDisplayCountry(Locale.GERMAN)))
                .sorted(Comparator.comparing(Country::name, collator))
                .toList();
    }
}
