package de.cgoit.faktura.catalog.web;

import de.cgoit.faktura.catalog.CatalogItemInfo;
import de.cgoit.faktura.shared.DecimalInput;
import jakarta.validation.constraints.NotBlank;

/**
 * Form data of a catalog entry. The price is typed in German notation and converted to cents on save.
 */
public record CatalogItemForm(
        String name,
        String description,
        @NotBlank(message = "{validation.required}") String unitId,
        @NotBlank(message = "{validation.required}") String price,
        String taxCategory) {

    static CatalogItemForm empty() {
        return new CatalogItemForm(null, null, null, null, null);
    }

    static CatalogItemForm from(CatalogItemInfo item) {
        return new CatalogItemForm(item.name(), item.description(), item.unitId(),
                DecimalInput.formatCents(item.unitPriceCents()), item.taxCategory());
    }
}
