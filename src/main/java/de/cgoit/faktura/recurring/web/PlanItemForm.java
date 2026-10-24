package de.cgoit.faktura.recurring.web;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import de.cgoit.faktura.recurring.PlanItemInput;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.ParseErrors;

/**
 * Form data of one item template. Numbers are typed in German notation; an empty tax category means "take it from
 * the tax profile of the period".
 */
public record PlanItemForm(
        String description,
        String quantity,
        String unitCode,
        String price,
        String discount,
        String taxCategory,
        String catalogItemId) {

    static PlanItemForm from(PlanItemInput item) {
        return new PlanItemForm(item.description(), DecimalInput.formatThousandths(item.quantityThousandths()),
                item.unitCode(), DecimalInput.formatCents(item.unitPriceCents()),
                DecimalInput.formatBasisPoints(item.discountBp()), item.taxCategory(), item.catalogItemId());
    }

    static PlanItemForm empty() {
        return new PlanItemForm("", "1", null, "0,00", "0", null, null);
    }

    /**
     * Converts the form to service input. An unreadable number is recorded in the errors with the property of the
     * input, including the row index.
     *
     * @param index  position of the row in the form
     * @param errors collects the unreadable numbers
     */
    PlanItemInput toInput(int index, ParseErrors errors) {
        var path = "items[" + index + "].";

        return new PlanItemInput(description,
                errors.parse(path + "quantityThousandths", () -> DecimalInput.parseThousandths(quantity), 0L),
                blankToNull(unitCode), errors.parse(path + "unitPriceCents", () -> DecimalInput.parseCents(price), 0L),
                errors.parse(path + "discountBp", () -> percent(discount), 0), blankToNull(taxCategory),
                blankToNull(catalogItemId));
    }

    private static int percent(String text) {
        return text == null || text.isBlank() ? 0 : DecimalInput.parseBasisPoints(text);
    }

    /**
     * Hides the free text from logs.
     */
    @Override
    public String toString() {
        return "PlanItemForm[...]";
    }
}
