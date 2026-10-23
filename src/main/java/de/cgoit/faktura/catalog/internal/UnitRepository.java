package de.cgoit.faktura.catalog.internal;

import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.catalog.UnitInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of units.
 */
@Repository
public class UnitRepository {

    private static final String SELECT = "SELECT id, code, name, name_plural, unece_code FROM unit";

    private final JdbcClient jdbc;

    public UnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UnitInfo> findAll() {
        return jdbc.sql(SELECT + " ORDER BY code")
                .query(UnitInfo.class)
                .list();
    }

    public Optional<UnitInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(UnitInfo.class)
                .optional();
    }

    public boolean existsByCode(String code, String exceptId) {
        var count = jdbc.sql("SELECT COUNT(*) FROM unit WHERE code = :code AND id <> :id")
                .param("code", code)
                .param("id", exceptId)
                .query(Long.class)
                .single();

        return count > 0;
    }

    public boolean isUsed(String id) {
        var count = jdbc.sql("SELECT COUNT(*) FROM catalog_item WHERE unit_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();

        return count > 0;
    }

    public void insert(UnitInfo unit) {
        jdbc.sql("INSERT INTO unit (id, code, name, name_plural, unece_code) "
                        + "VALUES (:id, :code, :name, :namePlural, :uneceCode)")
                .paramSource(unit)
                .update();
    }

    public void update(UnitInfo unit) {
        jdbc.sql("UPDATE unit SET code = :code, name = :name, name_plural = :namePlural, unece_code = :uneceCode "
                        + "WHERE id = :id")
                .paramSource(unit)
                .update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM unit WHERE id = :id")
                .param("id", id)
                .update();
    }
}
