package de.cgoit.faktura.shared;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.text.Collator;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class CountriesTest {

    @Test
    void containsGermanNamesForTheIsoCodes() {
        var byCode = Countries.all().stream()
                .collect(toMap(Countries.Country::code, Countries.Country::name));

        assertThat(byCode).containsEntry("DE", "Deutschland").containsEntry("AT", "Österreich")
                .containsEntry("CH", "Schweiz").containsEntry("XK", "Kosovo");
        assertThat(byCode.keySet()).allMatch(code -> code.length() == 2);
    }

    @Test
    void isSortedByGermanName() {
        var names = Countries.all().stream().map(Countries.Country::name).toList();

        assertThat(names).isSortedAccordingTo(Collator.getInstance(Locale.GERMAN)::compare);
        assertThat(names.indexOf("Österreich")).isLessThan(names.indexOf("Polen"));
    }

    @Test
    void knowsOnlyUpperCaseIsoCodes() {
        assertThat(Countries.isKnown("DE")).isTrue();
        assertThat(Countries.isKnown("de")).isFalse();
        assertThat(Countries.isKnown("XK")).isTrue();
        assertThat(Countries.isKnown("XX")).isFalse();
        assertThat(Countries.isKnown(null)).isFalse();
    }
}
