package de.cgoit.faktura.tax;

import java.time.LocalDate;

/**
 * Thrown when no tax profile is valid on the requested day.
 */
public class NoTaxProfileException extends RuntimeException {

    public NoTaxProfileException(LocalDate day) {
        super("No tax profile valid on " + day);
    }
}
