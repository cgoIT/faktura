package de.cgoit.faktura.document;

/**
 * One item as the calculation sees it.
 *
 * @param quantityThousandths quantity in thousandths (1,5 = 1500)
 * @param unitPriceCents      net price per unit in whole cents
 * @param discountBp          item discount in basis points
 * @param taxCategory         EN 16931 tax category
 * @param taxRateBp           tax rate in basis points
 */
public record CalculationItem(
        long quantityThousandths,
        long unitPriceCents,
        int discountBp,
        String taxCategory,
        int taxRateBp) {
}
