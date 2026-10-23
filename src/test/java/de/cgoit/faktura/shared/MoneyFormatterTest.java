package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyFormatterTest {

    private final MoneyFormatter money = new MoneyFormatter();

    @Test
    void formatsCentsWithGermanSeparators() {
        assertThat(money.format(119_000)).isEqualTo("1.190,00 €");
        assertThat(money.format(5)).isEqualTo("0,05 €");
        assertThat(money.format(-2_555)).isEqualTo("-25,55 €");
        assertThat(money.format(100_000_000)).isEqualTo("1.000.000,00 €");
    }
}
