package de.cgoit.faktura.company.web;

import de.cgoit.faktura.company.CompanyInfo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import de.cgoit.faktura.shared.Iban;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Form data of the company master data page.
 */
public record CompanyForm(
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}") String name,
        @Size(max = 200, message = "{validation.tooLong}") String owner,
        @Size(max = 200, message = "{validation.tooLong}") String street,
        @Size(max = 20, message = "{validation.tooLong}") String zip,
        @Size(max = 100, message = "{validation.tooLong}") String city,
        @Size(min = 2, max = 2, message = "{validation.country}") String country,
        @Size(max = 200, message = "{validation.tooLong}") String email,
        @Size(max = 50, message = "{validation.tooLong}") String phone,
        @Size(max = 50, message = "{validation.tooLong}") String taxNumber,
        @Size(max = 30, message = "{validation.tooLong}") String vatId,
        @Size(max = 42, message = "{validation.tooLong}") String iban,
        @Size(max = 11, message = "{validation.tooLong}") String bic,
        @Size(max = 100, message = "{validation.tooLong}") String bankName,
        @NotNull(message = "{validation.required}") @Min(value = 0, message = "{validation.paymentDays}")
        @Max(value = 365, message = "{validation.paymentDays}") Integer paymentDays,
        @NotNull(message = "{validation.required}") @Min(value = 1, message = "{validation.offerValidDays}")
        @Max(value = 365, message = "{validation.offerValidDays}") Integer offerValidDays) {

    static CompanyForm empty() {
        return new CompanyForm(null, null, null, null, null, "DE", null, null, null, null, null, null, null, 14,
                CompanyInfo.DEFAULT_OFFER_VALID_DAYS);
    }

    static CompanyForm from(CompanyInfo c) {
        return new CompanyForm(c.name(), c.owner(), c.street(), c.zip(), c.city(), c.country(), c.email(), c.phone(),
                c.taxNumber(), c.vatId(), Iban.format(c.iban()), c.bic(), c.bankName(), c.paymentDays(),
                c.offerValidDays());
    }

    CompanyInfo toInfo() {
        return new CompanyInfo(null, name, owner, street, zip, city, country, email, phone, taxNumber, vatId, iban,
                bic, bankName, paymentDays, offerValidDays);
    }
}
