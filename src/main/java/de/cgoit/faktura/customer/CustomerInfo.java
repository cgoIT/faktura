package de.cgoit.faktura.customer;

/**
 * Customer master data.
 *
 * @param number       customer number, assigned once and never changed
 * @param country      ISO 3166 alpha-2 code
 * @param paymentDays  payment term in days, {@code null} to use the default of the company
 * @param discountBp   default discount in basis points (500 = 5 %), {@code null} for none
 * @param einvoiceMode which electronic invoice the customer receives
 * @param mailRecipients addresses documents are sent to by mail (comma separated), {@code null}: contact person or invoice e-mail
 * @param archived     archived customers are hidden from selections but stay for existing documents
 */
public record CustomerInfo(
        String id,
        String number,
        String name,
        String street,
        String zip,
        String city,
        String country,
        String vatId,
        String invoiceEmail,
        Integer paymentDays,
        Integer discountBp,
        String note,
        EInvoiceMode einvoiceMode,
        boolean archived,
        String mailRecipients) {

    /**
     * Creates a customer without separate mail recipients.
     */
    public CustomerInfo(String id, String number, String name, String street, String zip, String city,
            String country, String vatId, String invoiceEmail, Integer paymentDays, Integer discountBp, String note,
            EInvoiceMode einvoiceMode, boolean archived) {
        this(id, number, name, street, zip, city, country, vatId, invoiceEmail, paymentDays, discountBp, note,
                einvoiceMode, archived, null);
    }

    /**
     * Creates a customer without electronic invoice.
     */
    public CustomerInfo(String id, String number, String name, String street, String zip, String city,
            String country, String vatId, String invoiceEmail, Integer paymentDays, Integer discountBp, String note,
            boolean archived) {
        this(id, number, name, street, zip, city, country, vatId, invoiceEmail, paymentDays, discountBp, note,
                EInvoiceMode.NONE, archived);
    }

    /**
     * Shows only the number: customer data must not end up in logs.
     */
    @Override
    public String toString() {
        return "CustomerInfo[number=" + number + "]";
    }
}
