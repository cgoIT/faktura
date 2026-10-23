package de.cgoit.faktura.catalog.internal;

import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.catalog.CatalogItemInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of catalog entries.
 */
@Repository
public class CatalogItemRepository {

    private static final String SELECT = """
            SELECT c.id, c.number, c.name, c.description, c.unit_id, u.code AS unit_code, u.name AS unit_name,
                   c.unit_price_cents, c.tax_category, c.active
            FROM catalog_item c
            JOIN unit u ON u.id = c.unit_id
            """;

    private final JdbcClient jdbc;

    public CatalogItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists entries ordered by number; {@code activeOnly} hides inactive ones.
     */
    public List<CatalogItemInfo> findAll(boolean activeOnly) {
        var where = activeOnly ? " WHERE c.active = 1" : "";

        return jdbc.sql(SELECT + where + " ORDER BY c.number")
                .query(CatalogItemInfo.class)
                .list();
    }

    public Optional<CatalogItemInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id")
                .param("id", id)
                .query(CatalogItemInfo.class)
                .optional();
    }

    public boolean unitExists(String unitId) {
        var count = jdbc.sql("SELECT COUNT(*) FROM unit WHERE id = :id")
                .param("id", unitId)
                .query(Long.class)
                .single();

        return count > 0;
    }

    public void insert(String id, String number, String name, String description, String unitId, long priceCents,
            String taxCategory) {
        jdbc.sql("""
                        INSERT INTO catalog_item (id, number, name, description, unit_id, unit_price_cents,
                                                  tax_category, active)
                        VALUES (:id, :number, :name, :description, :unitId, :price, :taxCategory, 1)
                        """)
                .param("id", id)
                .param("number", number)
                .param("name", name)
                .param("description", description)
                .param("unitId", unitId)
                .param("price", priceCents)
                .param("taxCategory", taxCategory)
                .update();
    }

    public void update(String id, String name, String description, String unitId, long priceCents,
            String taxCategory) {
        jdbc.sql("""
                        UPDATE catalog_item SET name = :name, description = :description, unit_id = :unitId,
                               unit_price_cents = :price, tax_category = :taxCategory
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("unitId", unitId)
                .param("price", priceCents)
                .param("taxCategory", taxCategory)
                .update();
    }

    public void setActive(String id, boolean active) {
        jdbc.sql("UPDATE catalog_item SET active = :active WHERE id = :id")
                .param("id", id)
                .param("active", active ? 1 : 0)
                .update();
    }
}
