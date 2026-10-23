package de.cgoit.faktura.recurring;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.document.TotalDiscount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Editable content of a recurring plan. The annotations are the rules for the single values, including those of the
 * items; the service checks them. Rules that need other data stay in the service.
 *
 * @param endDate       last day on which a period may start, {@code null} for no end
 * @param leadDays      days before a period starts on which its draft is generated
 * @param paymentDays   payment term of generated invoices, {@code null} for the customer's or the company's default
 * @param subject       subject of generated invoices, {@code null} for the plan name followed by the period
 * @param headerText    raw text with placeholders, {@code null} for the default header text
 * @param footerText    raw text with placeholders, {@code null} for the default footer text
 * @param totalDiscount discount on the whole invoice, {@code null} for the customer's default discount
 * @param contactId     contact person of the customer for the salutation, {@code null} for none
 * @param customerReference default customer reference of generated invoices, {@code null} for none
 */
public record PlanInput(
        @NotBlank(message = "{invoice.error.customer}") String customerId,
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}") String name,
        @NotNull(message = "{validation.required}") RecurringInterval interval,
        @NotNull(message = "{validation.required}") LocalDate anchorDate,
        LocalDate endDate,
        @Min(value = 0, message = "{recurring.error.leadDays}") @Max(value = 365, message = "{recurring.error.leadDays}")
        int leadDays,
        @NotNull(message = "{validation.required}") RecurringStatus status,
        @Min(value = 0, message = "{validation.paymentDays}") @Max(value = 365, message = "{validation.paymentDays}")
        Integer paymentDays,
        @Size(max = 300, message = "{validation.tooLong}") String subject,
        @Size(max = 4000, message = "{validation.tooLong}") String headerText,
        @Size(max = 4000, message = "{validation.tooLong}") String footerText,
        @Valid TotalDiscount totalDiscount,
        String contactId,
        @NotEmpty(message = "{invoice.error.noItems}")
        List<@NotNull(message = "{validation.required}") @Valid PlanItemInput> items,
        @Size(max = 200, message = "{validation.tooLong}") String customerReference) {

    /**
     * Creates a plan input without a customer reference.
     */
    public PlanInput(String customerId, String name, RecurringInterval interval, LocalDate anchorDate,
            LocalDate endDate, int leadDays, RecurringStatus status, Integer paymentDays, String subject,
            String headerText, String footerText, TotalDiscount totalDiscount, String contactId,
            List<PlanItemInput> items) {
        this(customerId, name, interval, anchorDate, endDate, leadDays, status, paymentDays, subject, headerText,
                footerText, totalDiscount, contactId, items, null);
    }

    /**
     * Hides the free texts from logs.
     */
    @Override
    public String toString() {
        return "PlanInput[items=" + items.size() + "]";
    }
}
