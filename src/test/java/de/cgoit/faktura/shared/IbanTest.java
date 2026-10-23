package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanTest {

    @Test
    void groupsByFour() {
        assertThat(Iban.format("DE02120300000000202051")).isEqualTo("DE02 1203 0000 0000 2020 51");
    }

    @Test
    void ignoresExistingBlanksAndKeepsShortRemainder() {
        assertThat(Iban.format("DE02 1203 0000 0000 2020 51")).isEqualTo("DE02 1203 0000 0000 2020 51");
        assertThat(Iban.format("NO9386011117947")).isEqualTo("NO93 8601 1117 947");
    }

    @Test
    void blankGivesNull() {
        assertThat(Iban.format(null)).isNull();
        assertThat(Iban.format("  ")).isNull();
    }
}
