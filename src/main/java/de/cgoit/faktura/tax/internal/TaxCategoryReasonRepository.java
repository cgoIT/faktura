package de.cgoit.faktura.tax.internal;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the user's reasons per tax category.
 */
@Repository
public class TaxCategoryReasonRepository {

    private final JdbcClient jdbc;

    public TaxCategoryReasonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> findAll() {
        var result = new HashMap<String, String>();

        jdbc.sql("SELECT category, reason FROM tax_category_reason")
                .query((rs, rowNum) -> result.put(rs.getString("category"), rs.getString("reason")))
                .list();

        return result;
    }

    public void save(String category, String reason) {
        delete(category);
        jdbc.sql("INSERT INTO tax_category_reason (category, reason) VALUES (:category, :reason)")
                .param("category", category)
                .param("reason", reason)
                .update();
    }

    public void delete(String category) {
        jdbc.sql("DELETE FROM tax_category_reason WHERE category = :category")
                .param("category", category)
                .update();
    }
}
