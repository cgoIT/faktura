package de.cgoit.faktura.document.internal;

import de.cgoit.faktura.customer.ContactInfo;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.EInvoiceMode;

/**
 * Copy of the customer data printed on a document, stored as JSON when the document is finalized.
 */
public record RecipientSnapshot(
        String number,
        String name,
        String street,
        String zip,
        String city,
        String country,
        String vatId,
        String invoiceEmail,
        String contactName,
        String contactSalutation,
        String contactLastName,
        EInvoiceMode einvoiceMode,
        String contactEmail,
        String contactFirstName,
        String mailRecipients) {

    /**
     * Snapshots stored before the electronic invoice existed have no mode; they count as none. Older snapshots have
     * no contact e-mail address and no mail recipients either.
     */
    public RecipientSnapshot {
        einvoiceMode = einvoiceMode == null ? EInvoiceMode.NONE : einvoiceMode;
    }

    /**
     * Creates a snapshot without separate mail recipients.
     */
    public RecipientSnapshot(String number, String name, String street, String zip, String city, String country,
            String vatId, String invoiceEmail, String contactName, String contactSalutation,
            String contactLastName, EInvoiceMode einvoiceMode, String contactEmail, String contactFirstName) {
        this(number, name, street, zip, city, country, vatId, invoiceEmail, contactName, contactSalutation,
                contactLastName, einvoiceMode, contactEmail, contactFirstName, null);
    }

    /**
     * Creates a snapshot without electronic invoice.
     */
    public RecipientSnapshot(String number, String name, String street, String zip, String city, String country,
            String vatId, String invoiceEmail, String contactName, String contactSalutation,
            String contactLastName) {
        this(number, name, street, zip, city, country, vatId, invoiceEmail, contactName, contactSalutation,
                contactLastName, EInvoiceMode.NONE, null, null, null);
    }

    /**
     * Copies the customer and, if given, the contact person chosen for the document.
     */
    public static RecipientSnapshot of(CustomerInfo customer, ContactInfo contact) {
        return new RecipientSnapshot(customer.number(), customer.name(), customer.street(), customer.zip(),
                customer.city(), customer.country(), customer.vatId(), customer.invoiceEmail(),
                contact == null ? null : contact.fullName(), contact == null ? null : contact.salutation(),
                contact == null ? null : contact.lastName(), customer.einvoiceMode(),
                contact == null ? null : contact.email(), contact == null ? null : contact.firstName(), customer.mailRecipients());
    }

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "RecipientSnapshot[number=" + number + "]";
    }
}
