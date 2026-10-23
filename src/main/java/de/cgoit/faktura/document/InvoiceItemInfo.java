package de.cgoit.faktura.document;

/**
 * Stored item of an invoice. It is a copy: later changes to catalog or units do not touch it.
 *
 * @param lineTotalCents line total after the item discount, in whole cents
 */
public record InvoiceItemInfo(
        String id,
        int position,
        String description,
        long quantityThousandths,
        String unitCode,
        String unitName,
        String unitNamePlural,
        String unitUneceCode,
        long unitPriceCents,
        int discountBp,
        String taxCategory,
        int taxRateBp,
        long lineTotalCents,
        String catalogItemId) {

    private static final long ONE_THOUSANDTHS = 1000;

    /**
     * Name of the unit for display: the singular for a quantity of exactly 1, the plural otherwise; without a plural
     * the name is used. Same rule as in the printed document.
     */
    public String unitLabel() {
        var plural = quantityThousandths != ONE_THOUSANDTHS
                && unitNamePlural != null
                && !unitNamePlural.isBlank();

        return plural ? unitNamePlural : unitName;
    }

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "InvoiceItemInfo[position=" + position + "]";
    }
}
