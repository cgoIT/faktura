package de.cgoit.faktura.document.web;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps the property of a violation of an {@code InvoiceInput} to the field of the editor form. Most names are equal;
 * the numbers of an item and the total discount are typed as text and have other names in the form.
 */
final class InvoiceFields {

    private static final Pattern ITEM = Pattern.compile("items\\[(\\d+)]\\.(\\w+)");
    private static final Map<String, String> ITEM_FIELDS = Map.of(
            "quantityThousandths", "quantity",
            "unitPriceCents", "price",
            "discountBp", "discount",
            "taxRateBp", "taxRate");

    private static final Map<String, String> SHIPPING_FIELDS = Map.of(
            "name", "shippingName",
            "cents", "shippingAmount",
            "taxCategory", "shippingTaxCategory",
            "taxRateBp", "shippingTaxRate");

    private InvoiceFields() {
    }

    /**
     * Returns the name of the form field, or {@code null} if the property has no field of its own (the violation
     * concerns the form as a whole or the field is not part of the page that reports it).
     */
    static String formField(String property) {
        if (property == null || property.isEmpty() || property.matches("items(\\[\\d+])?")
                || "contactId".equals(property)) {
            return null;
        }

        if (property.startsWith("shipping.")) {
            return SHIPPING_FIELDS.getOrDefault(property.substring("shipping.".length()), "shippingAmount");
        }

        if (property.startsWith("totalDiscount")) {
            return "discountValue";
        }

        var item = ITEM.matcher(property);

        if (item.matches()) {
            return "items[" + item.group(1) + "]." + ITEM_FIELDS.getOrDefault(item.group(2), item.group(2));
        }

        return property;
    }
}
