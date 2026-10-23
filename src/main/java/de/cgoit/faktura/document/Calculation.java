package de.cgoit.faktura.document;

import java.util.List;

/**
 * Result of a document calculation, all amounts in whole cents.
 *
 * @param lineTotals    line total per item after the item discount, in item order
 * @param subtotalCents sum of the line totals
 * @param discountCents total discount actually applied
 * @param shippingCents net shipping costs added after the discount, 0 without
 * @param netCents      subtotal minus total discount plus shipping
 * @param taxCents      sum of the tax of all groups
 * @param grossCents    net plus tax
 * @param taxTotals     tax per group, in order of first appearance
 */
public record Calculation(
        List<Long> lineTotals,
        long subtotalCents,
        long discountCents,
        long shippingCents,
        long netCents,
        long taxCents,
        long grossCents,
        List<TaxTotal> taxTotals) {
}
