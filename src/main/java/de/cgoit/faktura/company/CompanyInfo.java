package de.cgoit.faktura.company;

/**
 * Own company master data as printed on documents.
 *
 * @param id          identifier, {@code null} until the company is saved
 * @param country     ISO 3166 alpha-2 code
 * @param paymentDays default payment term in days
 * @param offerValidDays default validity of an offer in days
 */
public record CompanyInfo(
        String id,
        String name,
        String owner,
        String street,
        String zip,
        String city,
        String country,
        String email,
        String phone,
        String taxNumber,
        String vatId,
        String iban,
        String bic,
        String bankName,
        int paymentDays,
        int offerValidDays) {

    /**
     * Default validity of an offer, used where a company is created without a choice (the database default).
     */
    public static final int DEFAULT_OFFER_VALID_DAYS = 30;

    /**
     * Creates a company with the default offer validity.
     */
    public CompanyInfo(String id, String name, String owner, String street, String zip, String city, String country,
            String email, String phone, String taxNumber, String vatId, String iban, String bic, String bankName,
            int paymentDays) {
        this(id, name, owner, street, zip, city, country, email, phone, taxNumber, vatId, iban, bic, bankName,
                paymentDays, DEFAULT_OFFER_VALID_DAYS);
    }
}
