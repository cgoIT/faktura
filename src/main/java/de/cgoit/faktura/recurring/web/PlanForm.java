package de.cgoit.faktura.recurring.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.recurring.PlanInfo;
import de.cgoit.faktura.recurring.PlanInput;
import de.cgoit.faktura.recurring.PlanItemInput;
import de.cgoit.faktura.recurring.RecurringInterval;
import de.cgoit.faktura.recurring.RecurringStatus;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.ParseErrors;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form data of the plan editor. Dates come from date inputs (ISO), numbers in German notation. An empty discount
 * means "use the customer's discount", an empty payment term "use the default term".
 *
 * @param discountType {@code PERCENT} or {@code AMOUNT}
 * @param version the version of the plan when the editor opened it; empty for a new plan
 */
public record PlanForm(
        String customerId,
        String contactId,
        String name,
        RecurringInterval interval,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        String leadDays,
        RecurringStatus status,
        String paymentDays,
        String subject,
        String headerText,
        String footerText,
        String discountType,
        String discountValue,
        List<PlanItemForm> items,
        Long version,
        String customerReference) {

    static PlanForm empty(int defaultLeadDays, LocalDate anchor, String headerText, String footerText) {
        return new PlanForm(null, null, null, RecurringInterval.QUARTER, anchor, null,
                String.valueOf(defaultLeadDays), RecurringStatus.ACTIVE, null, null, headerText, footerText,
                "PERCENT", null, List.of(PlanItemForm.empty()), null, null);
    }

    static PlanForm from(PlanInfo plan) {
        var discount = plan.totalDiscount();
        var amount = discount != null && discount.type() == TotalDiscount.Type.AMOUNT;
        var value = discount == null ? null : switch (discount.type()) {
            case NONE -> null;
            case PERCENT -> DecimalInput.formatBasisPoints((int) discount.value());
            case AMOUNT -> DecimalInput.formatCents(discount.value());
        };

        return new PlanForm(plan.customerId(), plan.contactId(), plan.name(), plan.interval(), plan.anchorDate(),
                plan.endDate(), String.valueOf(plan.leadDays()), plan.status(),
                plan.paymentDays() == null ? null : String.valueOf(plan.paymentDays()), plan.subject(),
                plan.headerText(), plan.footerText(), amount ? "AMOUNT" : "PERCENT", value, plan.items()
                        .stream()
                        .map(PlanItemForm::from)
                        .toList(), plan.version(), plan.customerReference());
    }

    /**
     * Returns the version the editor read.
     *
     * @throws RuleViolationException with {@code recurring.error.conflict} if the form carries none
     */
    long requireVersion() {
        if (version == null) {
            throw new RuleViolationException("recurring.error.conflict");
        }

        return version;
    }

    PlanForm withItems(List<PlanItemForm> newItems) {
        return new PlanForm(customerId, contactId, name, interval, anchorDate, endDate, leadDays, status, paymentDays,
                subject, headerText, footerText, discountType, discountValue, newItems, version, customerReference);
    }

    /**
     * Returns a mutable copy of the items; a missing list counts as empty.
     */
    List<PlanItemForm> itemList() {
        return items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    /**
     * Converts the form to service input.
     *
     * @throws RuleViolationException with one violation per unreadable number, at the property of the input
     */
    PlanInput toInput() {
        var errors = new ParseErrors();
        var rows = itemList();
        var inputs = new ArrayList<PlanItemInput>();

        for (var index = 0; index < rows.size(); index++) {
            inputs.add(rows.get(index).toInput(index, errors));
        }

        var lead = errors.parse("leadDays", () -> integer(leadDays, "recurring.error.leadDays", 0), 0);
        var days = errors.parse("paymentDays",
                () -> blank(paymentDays) ? null : integer(paymentDays, "validation.paymentDays", 0), null);
        var totalDiscount = errors.parse("totalDiscount", this::discount, null);

        errors.throwIfAny();

        return new PlanInput(blank(customerId) ? null : customerId, name == null ? null : name.strip(),
                interval, anchorDate, endDate, lead, status == null ? RecurringStatus.ACTIVE : status, days,
                blankToNull(subject), blankToNull(headerText), blankToNull(footerText), totalDiscount,
                blank(contactId) ? null : contactId, inputs, blankToNull(customerReference));
    }

    private static int integer(String text, String errorKey, int fallback) {
        if (blank(text)) {
            return fallback;
        }

        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new RuleViolationException(errorKey);
        }
    }

    private TotalDiscount discount() {
        if (blank(discountValue)) {
            return null;
        }

        return "AMOUNT".equals(discountType)
                ? TotalDiscount.amount(DecimalInput.parseCents(discountValue))
                : TotalDiscount.percent(DecimalInput.parseBasisPoints(discountValue));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    /**
     * Hides the free texts from logs.
     */
    @Override
    public String toString() {
        return "PlanForm[...]";
    }
}
