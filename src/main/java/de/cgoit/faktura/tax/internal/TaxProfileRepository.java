package de.cgoit.faktura.tax.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.tax.TaxRegime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of tax profiles.
 */
@Repository
public class TaxProfileRepository {

    private static final String SELECT = """
            SELECT id, valid_from, valid_to, regime, default_rate_bp, default_category, exemption_text, notice_text,
                   allowed_categories
            FROM tax_profile
            """;

    private final JdbcClient jdbc;

    public TaxProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaxProfileInfo> findAll(String companyId) {
        return jdbc.sql(SELECT + " WHERE company_id = :companyId ORDER BY valid_from DESC")
                .param("companyId", companyId)
                .query(TaxProfileRepository::map)
                .list();
    }

    public Optional<TaxProfileInfo> findValidOn(String companyId, LocalDate day) {
        return jdbc.sql(SELECT + """
                        WHERE company_id = :companyId AND valid_from <= :day
                          AND (valid_to IS NULL OR valid_to >= :day)
                        """)
                .param("companyId", companyId)
                .param("day", day.toString())
                .query(TaxProfileRepository::map)
                .optional();
    }

    public void insert(String companyId, TaxProfileInfo profile) {
        jdbc.sql("""
                        INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                                 default_category, exemption_text, notice_text, allowed_categories)
                        VALUES (:id, :companyId, :validFrom, NULL, :regime, :rateBp, :category, :exemptionText,
                                :noticeText, :allowed)
                        """)
                .param("id", profile.id())
                .param("companyId", companyId)
                .param("validFrom", profile.validFrom().toString())
                .param("regime", profile.regime().name())
                .param("rateBp", profile.defaultRateBp())
                .param("category", profile.defaultCategory())
                .param("exemptionText", profile.exemptionText())
                .param("noticeText", profile.noticeText())
                .param("allowed", profile.allowedCategories().isEmpty()
                        ? null
                        : String.join(",", profile.allowedCategories()))
                .update();
    }

    /**
     * Ends the open-ended profile on the given day. Only {@code valid_to} of an open-ended profile is touched.
     */
    public void closeOpenProfile(String companyId, LocalDate lastDay) {
        jdbc.sql("UPDATE tax_profile SET valid_to = :lastDay WHERE company_id = :companyId AND valid_to IS NULL")
                .param("lastDay", lastDay.toString())
                .param("companyId", companyId)
                .update();
    }

    private static TaxProfileInfo map(ResultSet rs, int rowNum) throws SQLException {
        var validTo = rs.getString("valid_to");
        var allowed = rs.getString("allowed_categories");

        return new TaxProfileInfo(
                rs.getString("id"),
                LocalDate.parse(rs.getString("valid_from")),
                validTo == null ? null : LocalDate.parse(validTo),
                TaxRegime.valueOf(rs.getString("regime")),
                rs.getInt("default_rate_bp"),
                rs.getString("default_category"),
                rs.getString("exemption_text"),
                rs.getString("notice_text"),
                allowed == null || allowed.isBlank() ? List.of() : List.of(allowed.split(",")));
    }
}
