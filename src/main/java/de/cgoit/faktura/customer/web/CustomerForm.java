package de.cgoit.faktura.customer.web;

import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.MailTone;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.validation.constraints.Size;

/**
 * Form data of a customer. The discount is typed as a percentage in German notation and converted to basis points.
 */
public record CustomerForm(
        String name,
        String street,
        String zip,
        String city,
        String country,
        String vatId,
        String invoiceEmail,
        Integer paymentDays,
        @Size(max = 10, message = "{validation.percent}") String discount,
        String note,
        EInvoiceMode einvoiceMode,
        String mailRecipients,
        MailTone mailTone) {

    static CustomerForm empty() {
        return new CustomerForm(null, null, null, null, "DE", null, null, null, null, null,
                EInvoiceMode.ZUGFERD, null, null);
    }

    static CustomerForm from(CustomerInfo c) {
        var discount = c.discountBp() == null ? null : DecimalInput.formatBasisPoints(c.discountBp());

        return new CustomerForm(c.name(), c.street(), c.zip(), c.city(), c.country(), c.vatId(), c.invoiceEmail(),
                c.paymentDays(), discount, c.note(),
                c.einvoiceMode(), c.mailRecipients(), c.mailTone());
    }

    /**
     * Converts the form to service input.
     *
     * @throws RuleViolationException if the discount is not a percentage
     */
    CustomerInput toInput() {
        var discountBp = discount == null || discount.isBlank() ? null : DecimalInput.parseBasisPoints(discount);

        return new CustomerInput(name, street, zip, city, country, vatId, invoiceEmail, paymentDays, discountBp, note,
                einvoiceMode, mailRecipients, mailTone);
    }

    /**
     * Hides the personal data from logs.
     */
    @Override
    public String toString() {
        return "CustomerForm[...]";
    }
}
