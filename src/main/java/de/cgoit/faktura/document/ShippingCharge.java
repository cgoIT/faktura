package de.cgoit.faktura.document;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Shipping costs of a document: a net amount with its own tax category and rate. It is added after the total discount
 * and is not reduced by it. An empty tax category means "as the tax profile says".
 *
 * @param name        label printed on the document; required when the amount is not 0, it is the reason of the charge
 *                    in the e-invoice
 * @param cents       net amount in cents, 0 means no shipping costs
 * @param taxCategory EN 16931 tax category, empty for the profile default
 * @param taxRateBp   tax rate in basis points
 */
public record ShippingCharge(
        @Size(max = 100, message = "{validation.tooLong}") String name,
        @Min(value = 0, message = "{validation.money}") @Max(value = 9_999_999_999L, message = "{validation.money}")
        long cents,
        @Size(max = 2, message = "{validation.tooLong}") String taxCategory,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        int taxRateBp) {

    public static ShippingCharge none() {
        return new ShippingCharge(null, 0, null, 0);
    }

    /**
     * Tells whether the document carries shipping costs.
     */
    public boolean isPresent() {
        return cents != 0;
    }
}
