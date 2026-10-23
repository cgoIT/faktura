package de.cgoit.faktura.company;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.company.internal.CompanyRepository;
import de.cgoit.faktura.shared.Countries;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the single company of this installation.
 */
@Service
public class CompanyService {

    private static final int MAX_IBAN_LENGTH = 34;
    private static final int GERMAN_IBAN_LENGTH = 22;

    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) {
        this.companies = companies;
    }

    /**
     * Returns the company, or empty if the master data were not entered yet.
     */
    @Transactional(readOnly = true)
    public Optional<CompanyInfo> find() {
        return companies.findFirst();
    }

    /**
     * Saves the company data. The first call creates the company, later calls change it.
     * Blank optional values are stored as empty.
     *
     * @return the saved company including its identifier
     */
    @Transactional
    public CompanyInfo save(CompanyInfo input) {
        var company = normalize(input);
        var existing = companies.findFirst();

        if (existing.isPresent()) {
            var updated = withId(company, existing.get().id());
            companies.update(updated);

            return updated;
        }

        var created = withId(company, UUID.randomUUID().toString());
        companies.insert(created);

        return created;
    }

    private static CompanyInfo normalize(CompanyInfo c) {
        if (c.name() == null || c.name().isBlank()) {
            throw new IllegalArgumentException("Company name must not be blank");
        }

        if (c.paymentDays() < 0) {
            throw new IllegalArgumentException("Payment days must not be negative");
        }

        if (c.offerValidDays() < 0) {
            throw new IllegalArgumentException("Offer validity must not be negative");
        }

        var country = normalizeCountry(c.country());

        if (!Countries.isKnown(country)) {
            throw new RuleViolationException("validation.countryUnknown", "country");
        }

        return new CompanyInfo(
                c.id(),
                c.name().strip(),
                blankToNull(c.owner()),
                blankToNull(c.street()),
                blankToNull(c.zip()),
                blankToNull(c.city()),
                country,
                blankToNull(c.email()),
                blankToNull(c.phone()),
                blankToNull(c.taxNumber()),
                blankToNull(c.vatId()),
                normalizeIban(c.iban()),
                blankToNull(c.bic()),
                blankToNull(c.bankName()),
                c.paymentDays(),
                c.offerValidDays());
    }

    private static String normalizeCountry(String country) {
        if (country == null || country.isBlank()) {
            return "DE";
        }

        return country.strip().toUpperCase();
    }

    /**
     * Removes spaces, upper-cases and validates length and the ISO 7064 mod-97 check digits. The bank-specific account
     * number check methods of the Bundesbank are not applied.
     */
    private static String normalizeIban(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }

        var compact = iban.replaceAll("\\s", "").toUpperCase();

        if (compact.length() < 15 || compact.length() > MAX_IBAN_LENGTH || !hasValidChecksum(compact)
                || compact.startsWith("DE") && compact.length() != GERMAN_IBAN_LENGTH) {
            throw new RuleViolationException("validation.iban");
        }

        return compact;
    }

    private static boolean hasValidChecksum(String iban) {
        var rearranged = iban.substring(4) + iban.substring(0, 4);
        var digits = new StringBuilder();

        for (var character : rearranged.toCharArray()) {
            if (!Character.isLetterOrDigit(character)) {
                return false;
            }

            digits.append(Character.digit(character, 36));
        }

        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static CompanyInfo withId(CompanyInfo c, String id) {
        return new CompanyInfo(id, c.name(), c.owner(), c.street(), c.zip(), c.city(), c.country(), c.email(),
                c.phone(), c.taxNumber(), c.vatId(), c.iban(), c.bic(), c.bankName(), c.paymentDays(),
                c.offerValidDays());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
