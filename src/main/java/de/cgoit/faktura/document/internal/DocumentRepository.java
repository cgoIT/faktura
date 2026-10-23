package de.cgoit.faktura.document.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.cgoit.faktura.document.Calculation;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.ShippingCharge;
import de.cgoit.faktura.document.TaxTotal;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.shared.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of document headers and their tax totals. Every statement that changes or deletes data is limited to
 * {@code status = 'DRAFT'}, so a finalized document cannot be touched even by a bug further up.
 */
@Repository
public class DocumentRepository {

    private static final String SELECT = """
            SELECT id, type, status, number, customer_id, issue_date, service_from, service_to, due_date, subject,
                   header_text, footer_text, total_discount_type, total_discount_value, net_total_cents,
                   tax_total_cents, gross_total_cents, ref_document_id, recipient_snapshot, contact_id, created_by,
                   version, customer_reference, shipping_name, shipping_cents, shipping_tax_category,
                   shipping_tax_rate_bp, valid_until
            FROM document
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final CurrentUser currentUser;

    public DocumentRepository(JdbcClient jdbc, Clock clock, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.currentUser = currentUser;
    }

    /**
     * Header values of a document as stored; items and tax totals are loaded separately.
     */
    public record Header(
            String id,
            DocumentType type,
            DocumentStatus status,
            String number,
            String customerId,
            LocalDate issueDate,
            LocalDate serviceFrom,
            LocalDate serviceTo,
            LocalDate dueDate,
            String subject,
            String headerText,
            String footerText,
            TotalDiscount totalDiscount,
            long netTotalCents,
            long taxTotalCents,
            long grossTotalCents,
            String refDocumentId,
            String recipientSnapshot,
            String contactId,
            String createdBy,
            long version,
            String customerReference,
            ShippingCharge shipping,
            LocalDate validUntil) {

        /**
         * Shows only technical values: subject, texts and the recipient snapshot hold personal data.
         */
        @Override
        public String toString() {
            return "Header[id=" + id + ", type=" + type + ", status=" + status + "]";
        }
    }

    /**
     * Snapshots stored when a document is finalized, as JSON text.
     */
    public record Snapshots(String sender, String recipient, String tax) {

        /**
         * Hides personal and bank data from logs.
         */
        @Override
        public String toString() {
            return "Snapshots[]";
        }
    }

    /**
     * Editable header values of a draft.
     */
    public record DraftValues(
            String customerId,
            LocalDate issueDate,
            LocalDate serviceFrom,
            LocalDate serviceTo,
            LocalDate dueDate,
            String subject,
            String headerText,
            String footerText,
            TotalDiscount totalDiscount,
            String contactId,
            String customerReference,
            ShippingCharge shipping,
            LocalDate validUntil) {

        /**
         * Hides the free texts from logs.
         */
        @Override
        public String toString() {
            return "DraftValues[issueDate=" + issueDate + "]";
        }
    }

    /**
     * Lists the headers of one type, newest first, drafts before finalized documents with the same date.
     */
    public List<Header> findAll(DocumentType... types) {
        return jdbc.sql(SELECT + """
                 WHERE type IN (:types)
                 ORDER BY issue_date DESC, CASE WHEN number IS NULL THEN 1 ELSE 0 END DESC, number DESC,
                          created_at DESC""")
                .param("types", Arrays.stream(types)
                        .map(Enum::name)
                        .toList())
                .query(DocumentRepository::map)
                .list();
    }

    /**
     * Lists the finalized invoices and cancellations issued in a period, from the first day (inclusive) to the end
     * day (exclusive), oldest first. Drafts are never included.
     */
    public List<Header> findFinalIssuedBetween(LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + """
                 WHERE type IN ('INVOICE', 'CANCELLATION') AND status <> 'DRAFT'
                   AND issue_date >= :from AND issue_date < :to
                 ORDER BY issue_date, number""")
                .param("from", from.toString())
                .param("to", to.toString())
                .query(DocumentRepository::map)
                .list();
    }

    /**
     * Returns the stored tax totals of the finalized invoices and cancellations issued in a period, by document id.
     */
    public Map<String, List<TaxTotal>> findTaxTotalsIssuedBetween(LocalDate from, LocalDate to) {
        var totals = new HashMap<String, List<TaxTotal>>();

        jdbc.sql("""
                        SELECT t.document_id, t.category, t.rate_bp, t.basis_cents, t.tax_cents
                        FROM document_tax_total t JOIN document d ON d.id = t.document_id
                        WHERE d.type IN ('INVOICE', 'CANCELLATION') AND d.status <> 'DRAFT'
                          AND d.issue_date >= :from AND d.issue_date < :to
                        ORDER BY t.rate_bp DESC, t.category""")
                .param("from", from.toString())
                .param("to", to.toString())
                .query((rs, rowNum) -> totals.computeIfAbsent(rs.getString("document_id"), id -> new ArrayList<>())
                        .add(new TaxTotal(rs.getString("category"), rs.getInt("rate_bp"), rs.getLong("basis_cents"),
                                rs.getLong("tax_cents"))))
                .list();

        return totals;
    }

    /**
     * Lists the years in which a finalized invoice or cancellation was issued.
     */
    public List<Integer> findIssueYears() {
        return jdbc.sql("""
                        SELECT DISTINCT substr(issue_date, 1, 4) FROM document
                        WHERE type IN ('INVOICE', 'CANCELLATION') AND status <> 'DRAFT'""")
                .query(String.class)
                .list()
                .stream()
                .map(Integer::valueOf)
                .toList();
    }

    public Optional<Header> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(DocumentRepository::map)
                .optional();
    }

    public void insertDraft(String id, DocumentType type, DraftValues values, Calculation calculation) {
        insertDraft(id, type, null, values, calculation);
    }

    /**
     * Inserts a draft that refers to another document (the cancelled invoice of a cancellation).
     */
    public void insertDraft(String id, DocumentType type, String refDocumentId, DraftValues values,
            Calculation calculation) {
        var now = Instant.now(clock).toString();
        var discount = values.totalDiscount();
        var discountType = discount.type() == TotalDiscount.Type.NONE ? null : discount.type().name();

        jdbc.sql("""
                        INSERT INTO document (id, type, status, customer_id, issue_date, service_from, service_to,
                                              due_date, subject, header_text, footer_text, total_discount_type,
                                              total_discount_value, net_total_cents, tax_total_cents,
                                              gross_total_cents, ref_document_id, contact_id, created_by,
                                              customer_reference, shipping_name, shipping_cents,
                                              shipping_tax_category, shipping_tax_rate_bp, valid_until,
                                              created_at, updated_at)
                        VALUES (:id, :type, 'DRAFT', :customerId, :issueDate, :serviceFrom, :serviceTo, :dueDate,
                                :subject, :headerText, :footerText, :discountType, :discountValue, :net, :tax,
                                :gross, :refDocumentId, :contactId, :createdBy, :customerReference, :shippingName,
                                :shippingCents, :shippingCategory, :shippingRateBp, :validUntil, :now, :now)
                        """)
                .param("id", id)
                .param("type", type.name())
                .param("refDocumentId", refDocumentId)
                .param("contactId", values.contactId())
                .param("createdBy", currentUser.username())
                .param("customerReference", values.customerReference())
                .param("shippingName", values.shipping().name())
                .param("shippingCents", values.shipping().cents())
                .param("shippingCategory", values.shipping().taxCategory())
                .param("shippingRateBp", values.shipping().taxRateBp())
                .param("validUntil", text(values.validUntil()))
                .param("customerId", values.customerId())
                .param("issueDate", values.issueDate().toString())
                .param("serviceFrom", text(values.serviceFrom()))
                .param("serviceTo", text(values.serviceTo()))
                .param("dueDate", text(values.dueDate()))
                .param("subject", values.subject())
                .param("headerText", values.headerText())
                .param("footerText", values.footerText())
                .param("discountType", discountType)
                .param("discountValue", discountType == null ? null : discount.value())
                .param("net", calculation.netCents())
                .param("tax", calculation.taxCents())
                .param("gross", calculation.grossCents())
                .param("now", now)
                .update();
    }

    /**
     * Updates a draft and increments its version.
     *
     * @param expectedVersion the version the caller read; a draft saved by someone else in the meantime has a higher one
     * @return {@code true} if the draft was updated, {@code false} if it is missing, not a draft, or has another version
     */
    public boolean updateDraft(String id, DraftValues values, Calculation calculation, long expectedVersion) {
        var discount = values.totalDiscount();
        var discountType = discount.type() == TotalDiscount.Type.NONE ? null : discount.type().name();

        var updated = jdbc.sql("""
                        UPDATE document SET customer_id = :customerId, issue_date = :issueDate,
                               service_from = :serviceFrom, service_to = :serviceTo, due_date = :dueDate,
                               subject = :subject, header_text = :headerText, footer_text = :footerText,
                               total_discount_type = :discountType, total_discount_value = :discountValue,
                               net_total_cents = :net, tax_total_cents = :tax, gross_total_cents = :gross,
                               contact_id = :contactId, customer_reference = :customerReference,
                               shipping_name = :shippingName, shipping_cents = :shippingCents,
                               shipping_tax_category = :shippingCategory, shipping_tax_rate_bp = :shippingRateBp,
                               valid_until = :validUntil, updated_at = :now, version = version + 1
                        WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("id", id)
                .param("customerId", values.customerId())
                .param("issueDate", values.issueDate().toString())
                .param("serviceFrom", text(values.serviceFrom()))
                .param("serviceTo", text(values.serviceTo()))
                .param("dueDate", text(values.dueDate()))
                .param("subject", values.subject())
                .param("headerText", values.headerText())
                .param("footerText", values.footerText())
                .param("discountType", discountType)
                .param("discountValue", discountType == null ? null : discount.value())
                .param("net", calculation.netCents())
                .param("tax", calculation.taxCents())
                .param("gross", calculation.grossCents())
                .param("contactId", values.contactId())
                .param("customerReference", values.customerReference())
                .param("shippingName", values.shipping().name())
                .param("shippingCents", values.shipping().cents())
                .param("shippingCategory", values.shipping().taxCategory())
                .param("shippingRateBp", values.shipping().taxRateBp())
                .param("validUntil", text(values.validUntil()))
                .param("version", expectedVersion)
                .param("now", Instant.now(clock).toString())
                .update();

        return updated == 1;
    }

    /**
     * Deletes a draft together with its tax totals and items.
     *
     * @return {@code true} if a draft was deleted, {@code false} if the document is missing or not a draft
     */
    public boolean deleteDraft(String id) {
        var draft = jdbc.sql("SELECT COUNT(*) FROM document WHERE id = :id AND status = 'DRAFT'")
                .param("id", id)
                .query(Long.class)
                .single();

        if (draft == 0) {
            return false;
        }

        jdbc.sql("""
                        DELETE FROM document_tax_total
                        WHERE document_id = :id AND document_id IN (SELECT id FROM document WHERE status = 'DRAFT')
                        """)
                .param("id", id)
                .update();
        jdbc.sql("""
                        DELETE FROM document_item
                        WHERE document_id = :id AND document_id IN (SELECT id FROM document WHERE status = 'DRAFT')
                        """)
                .param("id", id)
                .update();

        return jdbc.sql("DELETE FROM document WHERE id = :id AND status = 'DRAFT'")
                .param("id", id)
                .update() == 1;
    }

    /**
     * Returns the ids of the invoices that were created from an offer, oldest first.
     */
    public List<String> findInvoiceIdsOfOffer(String offerId) {
        return jdbc.sql("""
                        SELECT id FROM document WHERE type = 'INVOICE' AND ref_document_id = :id
                        ORDER BY created_at, id
                        """)
                .param("id", offerId)
                .query(String.class)
                .list();
    }

    /**
     * Returns the id of the cancellation of an invoice, if it was cancelled.
     */
    public Optional<String> findCancellationOf(String invoiceId) {
        return jdbc.sql("SELECT id FROM document WHERE type = 'CANCELLATION' AND ref_document_id = :id")
                .param("id", invoiceId)
                .query(String.class)
                .optional();
    }

    /**
     * Returns the ids of all invoices that have a cancellation.
     */
    public Set<String> findCancelledInvoiceIds() {
        return new HashSet<>(jdbc.sql(
                        "SELECT ref_document_id FROM document WHERE type = 'CANCELLATION' AND ref_document_id IS NOT NULL")
                .query(String.class)
                .list());
    }

    /**
     * Returns the snapshots of a finalized document, empty for a draft.
     */
    public Optional<Snapshots> findSnapshots(String id) {
        return jdbc.sql("""
                        SELECT sender_snapshot, recipient_snapshot, tax_snapshot FROM document
                        WHERE id = :id AND status <> 'DRAFT'
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new Snapshots(rs.getString("sender_snapshot"),
                        rs.getString("recipient_snapshot"), rs.getString("tax_snapshot")))
                .optional();
    }

    /**
     * Finalizes a draft in one statement: number, totals, snapshots and status. This is the only statement that
     * changes a document's status, and it only matches drafts.
     *
     * @return {@code true} if a draft was finalized, {@code false} if the document is missing or not a draft
     */
    public boolean finalizeDraft(String id, String number, Calculation calculation, Snapshots snapshots,
            String headerText, String footerText) {
        var now = Instant.now(clock).toString();
        var updated = jdbc.sql("""
                        UPDATE document SET status = 'FINAL', number = :number, net_total_cents = :net,
                               tax_total_cents = :tax, gross_total_cents = :gross, header_text = :headerText,
                               footer_text = :footerText, sender_snapshot = :sender,
                               recipient_snapshot = :recipient, tax_snapshot = :taxSnapshot, finalized_at = :now,
                               updated_at = :now
                        WHERE id = :id AND status = 'DRAFT'
                        """)
                .param("id", id)
                .param("number", number)
                .param("net", calculation.netCents())
                .param("tax", calculation.taxCents())
                .param("gross", calculation.grossCents())
                .param("headerText", headerText)
                .param("footerText", footerText)
                .param("sender", snapshots.sender())
                .param("recipient", snapshots.recipient())
                .param("taxSnapshot", snapshots.tax())
                .param("now", now)
                .update();

        return updated == 1;
    }

    public List<TaxTotal> findTaxTotals(String documentId) {
        return jdbc.sql("""
                        SELECT category, rate_bp, basis_cents, tax_cents FROM document_tax_total
                        WHERE document_id = :id ORDER BY rate_bp DESC, category
                        """)
                .param("id", documentId)
                .query((rs, rowNum) -> new TaxTotal(rs.getString("category"), rs.getInt("rate_bp"),
                        rs.getLong("basis_cents"), rs.getLong("tax_cents")))
                .list();
    }

    /**
     * Replaces the stored tax totals of a draft.
     *
     * @throws IllegalStateException if the document is not a draft
     */
    public void replaceTaxTotals(String documentId, List<TaxTotal> totals) {
        requireDraft(documentId);

        jdbc.sql("""
                        DELETE FROM document_tax_total
                        WHERE document_id = :id AND document_id IN (SELECT id FROM document WHERE status = 'DRAFT')
                        """)
                .param("id", documentId)
                .update();

        for (var total : totals) {
            jdbc.sql("""
                            INSERT INTO document_tax_total (id, document_id, category, rate_bp, basis_cents, tax_cents)
                            VALUES (:id, :documentId, :category, :rateBp, :basis, :tax)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("documentId", documentId)
                    .param("category", total.category())
                    .param("rateBp", total.rateBp())
                    .param("basis", total.basisCents())
                    .param("tax", total.taxCents())
                    .update();
        }
    }

    private void requireDraft(String documentId) {
        var drafts = jdbc.sql("SELECT COUNT(*) FROM document WHERE id = :id AND status = 'DRAFT'")
                .param("id", documentId)
                .query(Long.class)
                .single();

        if (drafts == 0) {
            throw new IllegalStateException("Document is not a draft: " + documentId);
        }
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static Header map(ResultSet rs, int rowNum) throws SQLException {
        var discountType = rs.getString("total_discount_type");
        var discount = discountType == null
                ? TotalDiscount.none()
                : new TotalDiscount(TotalDiscount.Type.valueOf(discountType), rs.getLong("total_discount_value"));

        return new Header(
                rs.getString("id"),
                DocumentType.valueOf(rs.getString("type")),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("number"),
                rs.getString("customer_id"),
                LocalDate.parse(rs.getString("issue_date")),
                date(rs.getString("service_from")),
                date(rs.getString("service_to")),
                date(rs.getString("due_date")),
                rs.getString("subject"),
                rs.getString("header_text"),
                rs.getString("footer_text"),
                discount,
                rs.getLong("net_total_cents"),
                rs.getLong("tax_total_cents"),
                rs.getLong("gross_total_cents"),
                rs.getString("ref_document_id"),
                rs.getString("recipient_snapshot"),
                rs.getString("contact_id"),
                rs.getString("created_by"),
                rs.getLong("version"),
                rs.getString("customer_reference"),
                new ShippingCharge(rs.getString("shipping_name"), rs.getLong("shipping_cents"),
                        rs.getString("shipping_tax_category"), rs.getInt("shipping_tax_rate_bp")),
                date(rs.getString("valid_until")));
    }
}
