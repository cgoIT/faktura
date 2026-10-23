package de.cgoit.faktura.document;

/**
 * Tax of one group (category and rate) after the total discount.
 *
 * @param basisCents taxable amount after discount
 * @param taxCents   tax on the basis, rounded half up
 */
public record TaxTotal(String category, int rateBp, long basisCents, long taxCents) {
}
