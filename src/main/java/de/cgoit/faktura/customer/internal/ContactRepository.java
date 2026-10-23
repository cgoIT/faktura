package de.cgoit.faktura.customer.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cgoit.faktura.customer.ContactInfo;
import de.cgoit.faktura.customer.ContactInput;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of contact persons.
 */
@Repository
public class ContactRepository {

    private static final String SELECT = "SELECT id, customer_id, salutation, first_name, last_name, email, phone, mobile, role FROM customer_contact";

    private final JdbcClient jdbc;

    public ContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ContactInfo> findByCustomer(String customerId) {
        return jdbc.sql(SELECT + " WHERE customer_id = :customerId ORDER BY last_name, first_name, id")
                .param("customerId", customerId)
                .query(ContactInfo.class)
                .list();
    }

    public Optional<ContactInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(ContactInfo.class)
                .optional();
    }

    public void insert(String id, String customerId, ContactInput c) {
        jdbc.sql("""
                        INSERT INTO customer_contact (id, customer_id, salutation, first_name, last_name, email,
                                                      phone, mobile, role)
                        VALUES (:id, :customerId, :salutation, :firstName, :lastName, :email, :phone, :mobile,
                                :role)
                        """)
                .params(parameters(id, c))
                .param("customerId", customerId)
                .update();
    }

    public void update(String id, ContactInput c) {
        jdbc.sql("""
                        UPDATE customer_contact SET salutation = :salutation, first_name = :firstName, last_name = :lastName,
                               email = :email, phone = :phone, mobile = :mobile, role = :role
                        WHERE id = :id
                        """)
                .params(parameters(id, c))
                .update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM customer_contact WHERE id = :id")
                .param("id", id)
                .update();
    }

    private static Map<String, Object> parameters(String id, ContactInput c) {
        var parameters = new HashMap<String, Object>();
        parameters.put("id", id);
        parameters.put("salutation", c.salutation());
        parameters.put("firstName", c.firstName());
        parameters.put("lastName", c.lastName());
        parameters.put("email", c.email());
        parameters.put("phone", c.phone());
        parameters.put("mobile", c.mobile());
        parameters.put("role", c.role());

        return parameters;
    }
}
