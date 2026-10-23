package de.cgoit.faktura.catalog;

/**
 * A shipping option: master data the user picks from on an invoice. The invoice copies name, amount, category and rate,
 * so changing an option never changes a document.
 *
 * @param amountCents net amount in cents
 * @param taxCategory EN 16931 tax category, {@code null} to take the category of the tax profile
 * @param taxRateBp   tax rate in basis points, used with a category that carries a rate
 * @param sortOrder   position in the lists, smallest first
 * @param active      {@code false} for an option that is switched off and no longer offered
 */
public record ShippingOptionInfo(
        String id,
        String name,
        long amountCents,
        String taxCategory,
        int taxRateBp,
        int sortOrder,
        boolean active) {
}
