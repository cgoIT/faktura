package de.cgoit.faktura.document;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Item of an invoice as entered by the user. Catalog values are copied into the item; afterwards every value is free
 * to change.
 *
 * @param quantityThousandths quantity in thousandths (1,5 = 1500)
 * @param unitCode            code of a unit of measure, {@code null} for none
 * @param discountBp          item discount in basis points
 * @param taxCategory         EN 16931 tax category
 * @param taxRateBp           tax rate in basis points
 * @param catalogItemId       catalog entry the item was copied from, {@code null} for free text
 */
public record InvoiceItemInput(
        @NotBlank(message = "{validation.required}") @Size(max = 2000, message = "{validation.tooLong}")
        String description,
        @Min(value = 1, message = "{validation.quantity}") @Max(value = 999_999_999L, message = "{validation.quantity}")
        long quantityThousandths,
        String unitCode,
        @Min(value = 0, message = "{validation.money}") @Max(value = 9_999_999_999L, message = "{validation.money}")
        long unitPriceCents,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        int discountBp,
        String taxCategory,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        int taxRateBp,
        String catalogItemId) {

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "InvoiceItemInput[...]";
    }
}
