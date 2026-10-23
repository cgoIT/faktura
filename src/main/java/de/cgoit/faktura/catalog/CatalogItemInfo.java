package de.cgoit.faktura.catalog;

/**
 * Entry of the service catalog. It is a template for document items: inserting it into a document copies the values.
 *
 * @param unitPriceCents net price per unit in whole cents
 * @param taxCategory    EN 16931 tax category, {@code null} to take the category of the tax profile
 * @param active         whether the entry can be chosen for new documents
 */
public record CatalogItemInfo(
        String id,
        String number,
        String name,
        String description,
        String unitId,
        String unitCode,
        String unitName,
        long unitPriceCents,
        String taxCategory,
        boolean active) {
}
