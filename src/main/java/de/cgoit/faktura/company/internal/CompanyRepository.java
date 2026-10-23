package de.cgoit.faktura.company.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import de.cgoit.faktura.company.CompanyInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the company master data.
 */
@Repository
public class CompanyRepository {

    private static final String COLUMNS = """
            id, name, owner, street, zip, city, country, email, phone, tax_number, vat_id, iban, bic, bank_name,
            payment_days, offer_valid_days
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public CompanyRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<CompanyInfo> findFirst() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM company ORDER BY created_at, id LIMIT 1")
                .query((rs, rowNum) -> new CompanyInfo(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("owner"),
                        rs.getString("street"),
                        rs.getString("zip"),
                        rs.getString("city"),
                        rs.getString("country"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("tax_number"),
                        rs.getString("vat_id"),
                        rs.getString("iban"),
                        rs.getString("bic"),
                        rs.getString("bank_name"),
                        rs.getInt("payment_days"),
                        rs.getInt("offer_valid_days")))
                .optional();
    }

    /**
     * Inserts the company; the identifier is taken from {@code company.id()}.
     */
    public void insert(CompanyInfo company) {
        jdbc.sql("""
                        INSERT INTO company (id, name, owner, street, zip, city, country, email, phone, tax_number,
                                             vat_id, iban, bic, bank_name, payment_days, offer_valid_days,
                                             created_at)
                        VALUES (:id, :name, :owner, :street, :zip, :city, :country, :email, :phone, :taxNumber,
                                :vatId, :iban, :bic, :bankName, :paymentDays, :offerValidDays, :createdAt)
                        """)
                .params(parameters(company, Instant.now(clock).toString()))
                .update();
    }

    public void update(CompanyInfo company) {
        jdbc.sql("""
                        UPDATE company SET name = :name, owner = :owner, street = :street, zip = :zip, city = :city,
                               country = :country, email = :email, phone = :phone, tax_number = :taxNumber,
                               vat_id = :vatId, iban = :iban, bic = :bic, bank_name = :bankName,
                               payment_days = :paymentDays, offer_valid_days = :offerValidDays
                        WHERE id = :id
                        """)
                .params(parameters(company, null))
                .update();
    }

    private static Map<String, Object> parameters(CompanyInfo c, String createdAt) {
        var parameters = new HashMap<String, Object>();
        parameters.put("id", c.id());
        parameters.put("name", c.name());
        parameters.put("owner", c.owner());
        parameters.put("street", c.street());
        parameters.put("zip", c.zip());
        parameters.put("city", c.city());
        parameters.put("country", c.country());
        parameters.put("email", c.email());
        parameters.put("phone", c.phone());
        parameters.put("taxNumber", c.taxNumber());
        parameters.put("vatId", c.vatId());
        parameters.put("iban", c.iban());
        parameters.put("bic", c.bic());
        parameters.put("bankName", c.bankName());
        parameters.put("paymentDays", c.paymentDays());
        parameters.put("offerValidDays", c.offerValidDays());
        parameters.put("createdAt", createdAt);

        return parameters;
    }
}
