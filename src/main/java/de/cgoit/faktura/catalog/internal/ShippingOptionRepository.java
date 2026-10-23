package de.cgoit.faktura.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.catalog.ShippingOptionInfo;
import de.cgoit.faktura.catalog.ShippingOptionInput;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the shipping options. Options are configuration and are never deleted.
 */
@Repository
public class ShippingOptionRepository {

    private static final String SELECT = """
            SELECT id, name, amount_cents, tax_category, tax_rate_bp, sort_order, active
            FROM shipping_option
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ShippingOptionRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<ShippingOptionInfo> findAll(boolean activeOnly) {
        return jdbc.sql(SELECT + (activeOnly ? " WHERE active = 1" : "") + " ORDER BY sort_order, name, id")
                .query(ShippingOptionRepository::map)
                .list();
    }

    public Optional<ShippingOptionInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(ShippingOptionRepository::map)
                .optional();
    }

    public void insert(String id, ShippingOptionInput input) {
        jdbc.sql("""
                        INSERT INTO shipping_option (id, name, amount_cents, tax_category, tax_rate_bp, sort_order,
                                                     active, created_at)
                        VALUES (:id, :name, :amount, :category, :rate, :sortOrder, :active, :now)
                        """)
                .param("id", id)
                .param("name", input.name())
                .param("amount", input.amountCents())
                .param("category", input.taxCategory())
                .param("rate", input.taxRateBp())
                .param("sortOrder", input.sortOrder())
                .param("active", input.active() ? 1 : 0)
                .param("now", Instant.now(clock).toString())
                .update();
    }

    public void update(String id, ShippingOptionInput input) {
        jdbc.sql("""
                        UPDATE shipping_option
                        SET name = :name, amount_cents = :amount, tax_category = :category, tax_rate_bp = :rate,
                            sort_order = :sortOrder, active = :active
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("name", input.name())
                .param("amount", input.amountCents())
                .param("category", input.taxCategory())
                .param("rate", input.taxRateBp())
                .param("sortOrder", input.sortOrder())
                .param("active", input.active() ? 1 : 0)
                .update();
    }

    private static ShippingOptionInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new ShippingOptionInfo(rs.getString("id"), rs.getString("name"), rs.getLong("amount_cents"),
                rs.getString("tax_category"), rs.getInt("tax_rate_bp"), rs.getInt("sort_order"),
                rs.getInt("active") == 1);
    }
}
