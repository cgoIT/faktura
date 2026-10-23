package de.cgoit.faktura.document;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Editable content of an invoice draft. The annotations are the rules for the single values, including those of the
 * items; the service checks them. Rules that need other data stay in the service.
 *
 * @param paymentDays   payment term in days, {@code null} to use the customer's or the company's default
 * @param totalDiscount discount on the whole invoice, {@code null} to use the customer's default discount
 * @param contactId     contact person of the customer for the salutation, {@code null} for none
 * @param customerReference reference of the customer (order or project number), printed and sent as buyer reference
 * @param shipping      shipping costs, {@code null} for none
 * @param validUntil    last day an offer is valid, {@code null} to use the default validity of the company; ignored
 *                      for invoices
 */
public record InvoiceInput(
        String customerId,
        @NotNull(message = "{validation.required}") LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        @Min(value = 0, message = "{validation.paymentDays}") @Max(value = 365, message = "{validation.paymentDays}")
        Integer paymentDays,
        @Size(max = 300, message = "{validation.tooLong}") String subject,
        @Size(max = 4000, message = "{validation.tooLong}") String headerText,
        @Size(max = 4000, message = "{validation.tooLong}") String footerText,
        @Valid TotalDiscount totalDiscount,
        @NotNull(message = "{validation.required}")
        List<@NotNull(message = "{validation.required}") @Valid InvoiceItemInput> items,
        String contactId,
        @Size(max = 200, message = "{validation.tooLong}") String customerReference,
        @Valid ShippingCharge shipping,
        LocalDate validUntil) {

    /**
     * Creates an input without a validity date.
     */
    public InvoiceInput(String customerId, LocalDate issueDate, LocalDate serviceFrom, LocalDate serviceTo,
            Integer paymentDays, String subject, String headerText, String footerText, TotalDiscount totalDiscount,
            List<InvoiceItemInput> items, String contactId, String customerReference, ShippingCharge shipping) {
        this(customerId, issueDate, serviceFrom, serviceTo, paymentDays, subject, headerText, footerText,
                totalDiscount, items, contactId, customerReference, shipping, null);
    }

    /**
     * Creates an input without customer reference and shipping costs.
     */
    public InvoiceInput(String customerId, LocalDate issueDate, LocalDate serviceFrom, LocalDate serviceTo,
            Integer paymentDays, String subject, String headerText, String footerText, TotalDiscount totalDiscount,
            List<InvoiceItemInput> items, String contactId) {
        this(customerId, issueDate, serviceFrom, serviceTo, paymentDays, subject, headerText, footerText,
                totalDiscount, items, contactId, null, null);
    }

    /**
     * Creates an input without a contact person.
     */
    public InvoiceInput(String customerId, LocalDate issueDate, LocalDate serviceFrom, LocalDate serviceTo,
            Integer paymentDays, String subject, String headerText, String footerText, TotalDiscount totalDiscount,
            List<InvoiceItemInput> items) {
        this(customerId, issueDate, serviceFrom, serviceTo, paymentDays, subject, headerText, footerText,
                totalDiscount, items, null, null, null);
    }

    /**
     * Hides the free texts from logs.
     */
    @Override
    public String toString() {
        return "InvoiceInput[items=" + items.size() + "]";
    }
}
