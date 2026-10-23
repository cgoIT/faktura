package de.cgoit.faktura.document;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A payment to record. The annotations are the rules for the values; the service checks them. The date must not be in
 * the future, which depends on the clock and is checked by the service.
 *
 * @param amountCents positive amount in cents
 */
public record PaymentInput(
        @NotNull(message = "{validation.required}") LocalDate date,
        @Min(value = 1, message = "{payment.error.amount}") @Max(value = 9_999_999_999L, message = "{payment.error.amount}")
        long amountCents,
        @NotNull(message = "{validation.required}") PaymentMethod method,
        @Size(max = 200, message = "{validation.tooLong}") String reference,
        @Size(max = 500, message = "{validation.tooLong}") String note) {
}
