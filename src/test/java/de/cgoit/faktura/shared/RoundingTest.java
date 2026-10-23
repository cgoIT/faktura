package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoundingTest {

    @ParameterizedTest
    @CsvSource({"1,2,1", "1,3,0", "2,3,1", "5,10,1", "4,10,0", "15,10,2", "0,7,0", "1010,10000,0"})
    void dividesAndRoundsHalfUp(long numerator, long denominator, long expected) {
        assertThat(Rounding.divideHalfUp(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1,2,-1", "-1,3,0", "-5,10,-1", "-4,10,0", "-15,10,-2"})
    void roundsNegativeValuesSymmetrically(long numerator, long denominator, long expected) {
        assertThat(Rounding.divideHalfUp(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1010,500,51", "1050,1900,200", "100,50,1", "99,50,0", "0,1900,0"})
    void percentOfIsRoundedHalfUp(long value, long basisPoints, long expected) {
        assertThat(Rounding.percentOfHalfUp(value, basisPoints)).isEqualTo(expected);
    }
}
