package de.cgoit.faktura.recurring.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.recurring.PlanInfo;
import de.cgoit.faktura.recurring.PlanInput;
import de.cgoit.faktura.recurring.PlanItemInput;
import de.cgoit.faktura.recurring.RecurringInterval;
import de.cgoit.faktura.recurring.RecurringStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of plans and their item templates. Plans are configuration and may change at any time; generated
 * drafts and invoices never refer back to them except through the run record.
 */
@Repository
public class PlanRepository {

    private static final String SELECT = """
            SELECT id, customer_id, name, period_interval, anchor_date, end_date, lead_days, status,
                   next_period_index, payment_days, subject, header_text, footer_text, total_discount_type,
                   total_discount_value, contact_id, last_error, version, customer_reference
            FROM recurring_plan
            """;

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Header row of a plan without its items.
     */
    public record Row(String id, String customerId, String name, RecurringInterval interval, LocalDate anchorDate,
            LocalDate endDate, int leadDays, RecurringStatus status, int nextPeriodIndex, Integer paymentDays,
            String subject, String headerText, String footerText, TotalDiscount totalDiscount, String contactId,
            String lastError, long version, String customerReference) {
    }

    public List<Row> findAll() {
        return jdbc.sql(SELECT + " ORDER BY name, id")
                .query(PlanRepository::map)
                .list();
    }

    public List<Row> findByStatus(RecurringStatus status) {
        return jdbc.sql(SELECT + " WHERE status = :status ORDER BY name, id")
                .param("status", status.name())
                .query(PlanRepository::map)
                .list();
    }

    public Optional<Row> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(PlanRepository::map)
                .optional();
    }

    /**
     * Loads a plan with its items.
     */
    public Optional<PlanInfo> findInfo(String id) {
        return findById(id)
                .map(PlanRepository::toInfo)
                .map(this::withItems);
    }

    private PlanInfo withItems(PlanInfo info) {
        return new PlanInfo(info.id(), info.customerId(), info.name(), info.interval(), info.anchorDate(),
                info.endDate(), info.leadDays(), info.status(), info.nextPeriodIndex(), info.paymentDays(),
                info.subject(), info.headerText(), info.footerText(), info.totalDiscount(), info.contactId(),
                info.lastError(), findItems(info.id()), info.version(), info.customerReference());
    }

    private static PlanInfo toInfo(Row row) {
        return new PlanInfo(row.id(), row.customerId(), row.name(), row.interval(), row.anchorDate(), row.endDate(),
                row.leadDays(), row.status(), row.nextPeriodIndex(), row.paymentDays(), row.subject(),
                row.headerText(), row.footerText(), row.totalDiscount(), row.contactId(), row.lastError(), List.of(),
                row.version(), row.customerReference());
    }

    public List<PlanItemInput> findItems(String planId) {
        return jdbc.sql("""
                        SELECT description, quantity_thousandths, unit_code, unit_price_cents, discount_bp,
                               tax_category, catalog_item_id
                        FROM recurring_plan_item
                        WHERE plan_id = :planId
                        ORDER BY position
                        """)
                .param("planId", planId)
                .query((rs, rowNum) -> new PlanItemInput(rs.getString("description"),
                        rs.getLong("quantity_thousandths"), rs.getString("unit_code"), rs.getLong("unit_price_cents"),
                        rs.getInt("discount_bp"), rs.getString("tax_category"), rs.getString("catalog_item_id")))
                .list();
    }

    public void insert(String id, PlanInput input, int nextPeriodIndex, String now) {
        var discount = input.totalDiscount();

        jdbc.sql("""
                        INSERT INTO recurring_plan (id, customer_id, name, period_interval, anchor_date, end_date,
                            lead_days, status, next_period_index, payment_days, subject, header_text, footer_text,
                            total_discount_type, total_discount_value, contact_id, customer_reference, created_at, updated_at)
                        VALUES (:id, :customerId, :name, :interval, :anchor, :end, :lead, :status, :nextIndex,
                            :paymentDays, :subject, :header, :footer, :discountType, :discountValue, :contactId,
                            :customerReference, :now, :now)
                        """)
                .param("id", id)
                .param("customerId", input.customerId())
                .param("name", input.name())
                .param("interval", input.interval().name())
                .param("anchor", input.anchorDate().toString())
                .param("end", input.endDate() == null ? null : input.endDate().toString())
                .param("lead", input.leadDays())
                .param("status", input.status().name())
                .param("nextIndex", nextPeriodIndex)
                .param("paymentDays", input.paymentDays())
                .param("subject", input.subject())
                .param("header", input.headerText())
                .param("footer", input.footerText())
                .param("discountType", discountType(discount))
                .param("discountValue", discount == null ? 0 : discount.value())
                .param("contactId", input.contactId())
                .param("customerReference", input.customerReference())
                .param("now", now)
                .update();
    }

    /**
     * Updates a plan and increments its version.
     *
     * @return {@code false} if the plan has another version than {@code expectedVersion}
     */
    public boolean update(String id, PlanInput input, int nextPeriodIndex, String now, long expectedVersion) {
        var discount = input.totalDiscount();

        return jdbc.sql("""
                        UPDATE recurring_plan
                        SET customer_id = :customerId, name = :name, period_interval = :interval,
                            anchor_date = :anchor, end_date = :end, lead_days = :lead, status = :status,
                            next_period_index = :nextIndex, payment_days = :paymentDays, subject = :subject,
                            header_text = :header, footer_text = :footer, total_discount_type = :discountType,
                            total_discount_value = :discountValue, contact_id = :contactId, customer_reference = :customerReference, last_error = NULL,
                            updated_at = :now, version = version + 1
                        WHERE id = :id AND version = :version
                        """)
                .param("id", id)
                .param("customerId", input.customerId())
                .param("name", input.name())
                .param("interval", input.interval().name())
                .param("anchor", input.anchorDate().toString())
                .param("end", input.endDate() == null ? null : input.endDate().toString())
                .param("lead", input.leadDays())
                .param("status", input.status().name())
                .param("nextIndex", nextPeriodIndex)
                .param("paymentDays", input.paymentDays())
                .param("subject", input.subject())
                .param("header", input.headerText())
                .param("footer", input.footerText())
                .param("discountType", discountType(discount))
                .param("discountValue", discount == null ? 0 : discount.value())
                .param("contactId", input.contactId())
                .param("customerReference", input.customerReference())
                .param("now", now)
                .param("version", expectedVersion)
                .update() == 1;
    }

    public void replaceItems(String planId, List<PlanItemInput> items) {
        jdbc.sql("DELETE FROM recurring_plan_item WHERE plan_id = :planId")
                .param("planId", planId)
                .update();

        var position = 1;

        for (var item : items) {
            jdbc.sql("""
                            INSERT INTO recurring_plan_item (id, plan_id, position, description, quantity_thousandths,
                                unit_code, unit_price_cents, discount_bp, tax_category, catalog_item_id)
                            VALUES (:id, :planId, :position, :description, :quantity, :unit, :price, :discount,
                                :category, :catalogItemId)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("planId", planId)
                    .param("position", position++)
                    .param("description", item.description())
                    .param("quantity", item.quantityThousandths())
                    .param("unit", item.unitCode())
                    .param("price", item.unitPriceCents())
                    .param("discount", item.discountBp())
                    .param("category", item.taxCategory())
                    .param("catalogItemId", item.catalogItemId())
                    .update();
        }
    }

    /**
     * Moves the next period on, but only if the plan still has the state the caller worked with.
     *
     * @return {@code false} if the plan was changed in the meantime (index, anchor date or interval differ)
     */
    public boolean advanceNextPeriodIndex(PlanInfo plan) {
        return jdbc.sql("""
                        UPDATE recurring_plan SET next_period_index = :next, last_error = NULL
                        WHERE id = :id AND next_period_index = :expected AND anchor_date = :anchor
                          AND period_interval = :interval
                        """)
                .param("id", plan.id())
                .param("next", plan.nextPeriodIndex() + 1)
                .param("expected", plan.nextPeriodIndex())
                .param("anchor", plan.anchorDate().toString())
                .param("interval", plan.interval().name())
                .update() == 1;
    }

    public void updateStatus(String id, RecurringStatus status, String now) {
        jdbc.sql("UPDATE recurring_plan SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .param("now", now)
                .update();
    }

    public void updateLastError(String id, String messageKey) {
        jdbc.sql("UPDATE recurring_plan SET last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", messageKey)
                .update();
    }

    private static String discountType(TotalDiscount discount) {
        return discount == null || discount.type() == TotalDiscount.Type.NONE ? null : discount.type().name();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        var type = rs.getString("total_discount_type");
        TotalDiscount discount = type == null
                ? null
                : new TotalDiscount(TotalDiscount.Type.valueOf(type), rs.getLong("total_discount_value"));
        var end = rs.getString("end_date");
        var paymentDays = rs.getObject("payment_days") == null ? null : rs.getInt("payment_days");

        return new Row(rs.getString("id"), rs.getString("customer_id"), rs.getString("name"),
                RecurringInterval.valueOf(rs.getString("period_interval")), LocalDate.parse(rs.getString("anchor_date")),
                end == null ? null : LocalDate.parse(end), rs.getInt("lead_days"),
                RecurringStatus.valueOf(rs.getString("status")), rs.getInt("next_period_index"), paymentDays,
                rs.getString("subject"), rs.getString("header_text"), rs.getString("footer_text"), discount,
                rs.getString("contact_id"), rs.getString("last_error"), rs.getLong("version"),
                rs.getString("customer_reference"));
    }
}
