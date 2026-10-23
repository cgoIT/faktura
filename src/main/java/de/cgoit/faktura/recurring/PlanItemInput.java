package de.cgoit.faktura.recurring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Item template of a plan. It becomes an invoice item with every generated draft.
 *
 * @param quantityThousandths quantity in thousandths (1,5 = 1500)
 * @param unitCode            code of a unit of measure, {@code null} for none
 * @param discountBp          item discount in basis points
 * @param taxCategory         EN 16931 tax category, {@code null} to take the category of the tax profile of the period
 * @param catalogItemId       catalog entry the template was copied from, {@code null} for free text
 */
public record PlanItemInput(
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
        String catalogItemId) {

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "PlanItemInput[...]";
    }
}
