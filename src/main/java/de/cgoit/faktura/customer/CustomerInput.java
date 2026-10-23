package de.cgoit.faktura.customer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Editable customer values. Blank optional values are stored as empty. The annotations are the rules for the values;
 * the service checks them after normalizing the input.
 *
 * @param paymentDays term in days, {@code null} to use the default of the company
 * @param discountBp  default discount in basis points, {@code null} for none
 * @param einvoiceMode which electronic invoice the customer receives, {@code null} means none
 */
public record CustomerInput(
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}") String name,
        @Size(max = 200, message = "{validation.tooLong}") String street,
        @Size(max = 20, message = "{validation.tooLong}") String zip,
        @Size(max = 100, message = "{validation.tooLong}") String city,
        @Size(min = 2, max = 2, message = "{validation.country}") String country,
        String vatId,
        @Size(max = 200, message = "{validation.tooLong}") String invoiceEmail,
        @Min(value = 0, message = "{validation.paymentDays}") @Max(value = 365, message = "{validation.paymentDays}")
        Integer paymentDays,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        Integer discountBp,
        @Size(max = 2000, message = "{validation.tooLong}") String note,
        EInvoiceMode einvoiceMode) {

    /**
     * Creates input without electronic invoice.
     */
    public CustomerInput(String name, String street, String zip, String city, String country, String vatId,
            String invoiceEmail, Integer paymentDays, Integer discountBp, String note) {
        this(name, street, zip, city, country, vatId, invoiceEmail, paymentDays, discountBp, note,
                EInvoiceMode.NONE);
    }

    /**
     * Hides the personal data from logs.
     */
    @Override
    public String toString() {
        return "CustomerInput[...]";
    }
}
