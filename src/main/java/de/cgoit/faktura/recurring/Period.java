package de.cgoit.faktura.recurring;

import java.time.LocalDate;

/**
 * One billing period, both days included.
 *
 * @param index position of the period counted from the anchor date, starting at 0
 */
public record Period(int index, LocalDate from, LocalDate to) {
}
