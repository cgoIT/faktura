package de.cgoit.faktura.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Editable values of a catalog entry.
 *
 * @param unitPriceCents net price per unit in whole cents
 * @param taxCategory    EN 16931 tax category, blank to take the category of the tax profile
 */
public record CatalogItemInput(
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}") String name,
        @Size(max = 2000, message = "{validation.tooLong}") String description,
        String unitId,
        @Min(value = 0, message = "{validation.money}") long unitPriceCents,
        String taxCategory) {
}
