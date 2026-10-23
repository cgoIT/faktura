package de.cgoit.faktura.document.internal;

import de.cgoit.faktura.company.CompanyInfo;

/**
 * Copy of the company data printed on a document, stored as JSON when the document is finalized.
 */
public record SenderSnapshot(
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
        String bankName) {

    public static SenderSnapshot of(CompanyInfo company) {
        return new SenderSnapshot(company.name(), company.owner(), company.street(), company.zip(), company.city(),
                company.country(), company.email(), company.phone(), company.taxNumber(), company.vatId(),
                company.iban(), company.bic(), company.bankName());
    }

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "SenderSnapshot[]";
    }
}
