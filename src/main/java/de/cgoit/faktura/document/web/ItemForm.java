package de.cgoit.faktura.document.web;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import de.cgoit.faktura.document.InvoiceItemInfo;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.ParseErrors;

/**
 * Form data of one invoice item. Numbers are typed in German notation and converted on save. Tax category and catalog
 * origin travel as hidden fields.
 */
public record ItemForm(
        String description,
        String quantity,
        String unitCode,
        String price,
        String discount,
        String taxCategory,
        String taxRate,
        String catalogItemId) {

    static ItemForm from(InvoiceItemInfo item) {
        return new ItemForm(item.description(), DecimalInput.formatThousandths(item.quantityThousandths()),
                item.unitCode(), DecimalInput.formatCents(item.unitPriceCents()),
                DecimalInput.formatBasisPoints(item.discountBp()), item.taxCategory(),
                DecimalInput.formatBasisPoints(item.taxRateBp()), item.catalogItemId());
    }

    static ItemForm from(InvoiceItemInput item) {
        return new ItemForm(item.description(), DecimalInput.formatThousandths(item.quantityThousandths()),
                item.unitCode(), DecimalInput.formatCents(item.unitPriceCents()),
                DecimalInput.formatBasisPoints(item.discountBp()), item.taxCategory(),
                DecimalInput.formatBasisPoints(item.taxRateBp()), item.catalogItemId());
    }

    /**
     * Converts the form to service input; empty discount and tax rate count as 0 %. An unreadable number is recorded
     * in the errors with the property of the input, including the row index.
     *
     * @param index  position of the row in the form
     * @param errors collects the unreadable numbers
     */
    InvoiceItemInput toInput(int index, ParseErrors errors) {
        var path = "items[" + index + "].";

        return new InvoiceItemInput(description,
                errors.parse(path + "quantityThousandths", () -> DecimalInput.parseThousandths(quantity), 0L),
                unitCode, errors.parse(path + "unitPriceCents", () -> DecimalInput.parseCents(price), 0L),
                errors.parse(path + "discountBp", () -> percent(discount), 0), taxCategory,
                errors.parse(path + "taxRateBp", () -> percent(taxRate), 0), blankToNull(catalogItemId));
    }

    private static int percent(String text) {
        return text == null || text.isBlank() ? 0 : DecimalInput.parseBasisPoints(text);
    }

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "ItemForm[...]";
    }
}
