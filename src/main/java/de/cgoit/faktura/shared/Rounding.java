package de.cgoit.faktura.shared;

import java.math.BigInteger;

/**
 * Integer rounding helpers for money calculations. Everything is exact; no floating point is involved.
 */
public final class Rounding {

    private Rounding() {
    }

    /**
     * Divides by a positive denominator and rounds half away from zero (commercial rounding): the magnitude is
     * rounded half up and the sign is kept, so cancellations with negative amounts round like the original.
     *
     * @throws ArithmeticException if the result does not fit into a {@code long}
     */
    public static long divideHalfUp(BigInteger numerator, BigInteger denominator) {
        var doubled = numerator.abs()
                .shiftLeft(1)
                .add(denominator);
        var magnitude = doubled.divide(denominator.shiftLeft(1));

        return (numerator.signum() < 0 ? magnitude.negate() : magnitude).longValueExact();
    }

    /**
     * Returns {@code value * basisPoints / 10,000}, rounded half up.
     */
    public static long percentOfHalfUp(long value, long basisPoints) {
        return divideHalfUp(BigInteger.valueOf(value).multiply(BigInteger.valueOf(basisPoints)),
                BigInteger.valueOf(10_000));
    }
}
