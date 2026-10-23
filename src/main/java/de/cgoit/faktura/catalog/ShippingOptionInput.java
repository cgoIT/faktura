package de.cgoit.faktura.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Values of a shipping option to save; see {@link ShippingOptionInfo} for the meaning. The annotations are the rules for
 * the values; the service checks them.
 */
public record ShippingOptionInput(
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String name,
        @Min(value = 0, message = "{validation.money}") @Max(value = 9_999_999_999L, message = "{validation.money}")
        long amountCents,
        @Size(max = 2, message = "{validation.tooLong}") String taxCategory,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        int taxRateBp,
        @Min(value = 0, message = "{shipping.error.sortOrder}") @Max(value = 9999, message = "{shipping.error.sortOrder}")
        int sortOrder,
        boolean active) {
}
