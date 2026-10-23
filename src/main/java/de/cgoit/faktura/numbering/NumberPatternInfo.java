package de.cgoit.faktura.numbering;

/**
 * Configured number pattern of a scope together with the number that would be assigned next.
 *
 * @param nextNumber example of the next number for the current period; it is not reserved
 */
public record NumberPatternInfo(NumberScope scope, String pattern, String nextNumber) {
}
