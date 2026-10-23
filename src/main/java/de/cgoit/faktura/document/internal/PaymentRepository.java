package de.cgoit.faktura.document.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cgoit.faktura.document.PaymentExportRow;
import de.cgoit.faktura.document.PaymentInfo;
import de.cgoit.faktura.document.PaymentMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of payments. The table is append-only: this class only inserts and reads. A payment is corrected by
 * a reversing entry, never by an update or delete.
 */
@Repository
public class PaymentRepository {

    private static final String SELECT = """
            SELECT p.id, p.document_id, p.payment_date, p.amount_cents, p.method, p.reference, p.note,
                   p.reverses_payment_id,
                   (SELECT r.id FROM payment r WHERE r.reverses_payment_id = p.id) AS reversed_by_id
            FROM payment p
            """;

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String documentId, LocalDate date, long amountCents, PaymentMethod method,
            String reference, String note, String reversesPaymentId, String createdAt) {
        jdbc.sql("""
                        INSERT INTO payment (id, document_id, payment_date, amount_cents, method, reference, note,
                                             reverses_payment_id, created_at)
                        VALUES (:id, :documentId, :date, :amount, :method, :reference, :note, :reverses, :createdAt)
                        """)
                .param("id", id)
                .param("documentId", documentId)
                .param("date", date.toString())
                .param("amount", amountCents)
                .param("method", method.name())
                .param("reference", reference)
                .param("note", note)
                .param("reverses", reversesPaymentId)
                .param("createdAt", createdAt)
                .update();
    }

    /**
     * Lists the payments of a document in booking order.
     */
    public List<PaymentInfo> findByDocument(String documentId) {
        return jdbc.sql(SELECT + " WHERE p.document_id = :documentId ORDER BY p.payment_date, p.created_at, p.id")
                .param("documentId", documentId)
                .query(PaymentRepository::map)
                .list();
    }

    public Optional<PaymentInfo> findById(String id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id")
                .param("id", id)
                .query(PaymentRepository::map)
                .optional();
    }

    /**
     * Returns the sum of all payments per document id, reversing entries included. Documents without payments are
     * missing from the map.
     */
    public Map<String, Long> sumsByDocument() {
        var sums = new HashMap<String, Long>();

        jdbc.sql("SELECT document_id, SUM(amount_cents) AS total FROM payment GROUP BY document_id")
                .query((rs, rowNum) -> sums.put(rs.getString("document_id"), rs.getLong("total")))
                .list();

        return sums;
    }

    /**
     * Lists the payments booked in a period, from the first day (inclusive) to the end day (exclusive), with the number
     * of their invoice, oldest first.
     */
    public List<PaymentExportRow> findForExport(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT p.payment_date, d.number, p.amount_cents, p.method, p.reference,
                               p.reverses_payment_id
                        FROM payment p JOIN document d ON d.id = p.document_id
                        WHERE p.payment_date >= :from AND p.payment_date < :to
                        ORDER BY p.payment_date, d.number, p.created_at, p.id""")
                .param("from", from.toString())
                .param("to", to.toString())
                .query((rs, rowNum) -> new PaymentExportRow(LocalDate.parse(rs.getString("payment_date")),
                        rs.getString("number"), rs.getLong("amount_cents"),
                        PaymentMethod.valueOf(rs.getString("method")), rs.getString("reference"),
                        rs.getString("reverses_payment_id") != null))
                .list();
    }

    /**
     * Returns the date of the latest payment per document id; documents without payments are missing.
     */
    public Map<String, LocalDate> lastPaymentDates() {
        var dates = new HashMap<String, LocalDate>();

        jdbc.sql("SELECT document_id, MAX(payment_date) AS last_date FROM payment GROUP BY document_id")
                .query((rs, rowNum) -> dates.put(rs.getString("document_id"),
                        LocalDate.parse(rs.getString("last_date"))))
                .list();

        return dates;
    }

    /**
     * Lists the years in which a payment was booked.
     */
    public List<Integer> findPaymentYears() {
        return jdbc.sql("SELECT DISTINCT substr(payment_date, 1, 4) FROM payment")
                .query(String.class)
                .list()
                .stream()
                .map(Integer::valueOf)
                .toList();
    }

    public long sumOf(String documentId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM payment WHERE document_id = :id")
                .param("id", documentId)
                .query(Long.class)
                .single();
    }

    private static PaymentInfo map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentInfo(
                rs.getString("id"),
                rs.getString("document_id"),
                LocalDate.parse(rs.getString("payment_date")),
                rs.getLong("amount_cents"),
                PaymentMethod.valueOf(rs.getString("method")),
                rs.getString("reference"),
                rs.getString("note"),
                rs.getString("reverses_payment_id"),
                rs.getString("reversed_by_id"));
    }
}
