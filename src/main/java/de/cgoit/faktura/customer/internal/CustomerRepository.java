package de.cgoit.faktura.customer.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerInput;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of customers.
 */
@Repository
public class CustomerRepository {

    private static final String SELECT = """
            SELECT id, number, name, street, zip, city, country, vat_id, invoice_email, payment_days, discount_bp,
                   note, einvoice_mode, archived
            FROM customer
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Lists customers ordered by name; {@code archived} {@code null} returns both kinds.
     */
    public List<CustomerInfo> findAll(Boolean archived) {
        var where = archived == null ? "" : " WHERE archived = :archived";
        var statement = jdbc.sql(SELECT + where + " ORDER BY name, number");

        if (archived != null) {
            statement = statement.param("archived", archived ? 1 : 0);
        }

        return statement.query(CustomerInfo.class).list();
    }

    public Optional<CustomerInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(CustomerInfo.class)
                .optional();
    }

    public void insert(String id, String number, CustomerInput c) {
        var createdAt = Instant.now(clock).toString();

        jdbc.sql("""
                        INSERT INTO customer (id, number, name, street, zip, city, country, vat_id, invoice_email,
                                              payment_days, discount_bp, note, einvoice_mode, archived, created_at)
                        VALUES (:id, :number, :name, :street, :zip, :city, :country, :vatId, :invoiceEmail,
                                :paymentDays, :discountBp, :note, :einvoiceMode, 0, :createdAt)
                        """)
                .params(parameters(id, c))
                .param("number", number)
                .param("createdAt", createdAt)
                .update();
    }

    public void update(String id, CustomerInput c) {
        jdbc.sql("""
                        UPDATE customer SET name = :name, street = :street, zip = :zip, city = :city,
                               country = :country, vat_id = :vatId, invoice_email = :invoiceEmail,
                               payment_days = :paymentDays, discount_bp = :discountBp, note = :note,
                               einvoice_mode = :einvoiceMode
                        WHERE id = :id
                        """)
                .params(parameters(id, c))
                .update();
    }

    public void setArchived(String id, boolean archived) {
        jdbc.sql("UPDATE customer SET archived = :archived WHERE id = :id")
                .param("id", id)
                .param("archived", archived ? 1 : 0)
                .update();
    }

    private static Map<String, Object> parameters(String id, CustomerInput c) {
        var parameters = new HashMap<String, Object>();
        parameters.put("id", id);
        parameters.put("name", c.name());
        parameters.put("street", c.street());
        parameters.put("zip", c.zip());
        parameters.put("city", c.city());
        parameters.put("country", c.country());
        parameters.put("vatId", c.vatId());
        parameters.put("invoiceEmail", c.invoiceEmail());
        parameters.put("paymentDays", c.paymentDays());
        parameters.put("discountBp", c.discountBp());
        parameters.put("note", c.note());
        parameters.put("einvoiceMode", c.einvoiceMode().name());

        return parameters;
    }
}
