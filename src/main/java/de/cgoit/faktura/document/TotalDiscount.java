package de.cgoit.faktura.document;

import jakarta.validation.constraints.AssertTrue;

/**
 * Discount on the whole document, given either as percentage (basis points) or as a fixed amount (cents).
 *
 * @param value basis points for {@link Type#PERCENT}, cents for {@link Type#AMOUNT}, ignored for {@link Type#NONE}
 */
public record TotalDiscount(Type type, long value) {

    /**
     * How the discount value is interpreted.
     */
    public enum Type {
        NONE, PERCENT, AMOUNT
    }

    /**
     * Tells whether the value is a valid amount or percentage: not negative, and a percentage at most 100 %.
     */
    @AssertTrue(message = "{validation.percent}")
    public boolean isValid() {
        return value >= 0 && (type != Type.PERCENT || value <= 10_000);
    }

    public static TotalDiscount none() {
        return new TotalDiscount(Type.NONE, 0);
    }

    public static TotalDiscount percent(int basisPoints) {
        return new TotalDiscount(Type.PERCENT, basisPoints);
    }

    public static TotalDiscount amount(long cents) {
        return new TotalDiscount(Type.AMOUNT, cents);
    }
}
