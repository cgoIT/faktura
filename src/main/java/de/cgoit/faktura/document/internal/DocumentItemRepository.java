package de.cgoit.faktura.document.internal;

import java.util.List;
import java.util.UUID;

import de.cgoit.faktura.document.InvoiceItemInfo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of document items. Items of finalized documents are never deleted: the delete is limited to drafts.
 */
@Repository
public class DocumentItemRepository {

    private final JdbcClient jdbc;

    public DocumentItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<InvoiceItemInfo> findByDocument(String documentId) {
        return jdbc.sql("""
                        SELECT id, position, description, quantity_thousandths, unit_code, unit_name,
                               unit_name_plural, unit_unece_code, unit_price_cents, discount_bp, tax_category,
                               tax_rate_bp, line_total_cents, catalog_item_id
                        FROM document_item
                        WHERE document_id = :documentId
                        ORDER BY position
                        """)
                .param("documentId", documentId)
                .query(InvoiceItemInfo.class)
                .list();
    }

    /**
     * Removes all items of a draft. The caller has verified that the document is a draft.
     */
    public void deleteOfDraft(String documentId) {
        jdbc.sql("""
                        DELETE FROM document_item
                        WHERE document_id = :id AND document_id IN (SELECT id FROM document WHERE status = 'DRAFT')
                        """)
                .param("id", documentId)
                .update();
    }

    /**
     * Inserts an item as a copy of the given values.
     *
     * @throws IllegalStateException if the document is not a draft
     */
    public void insert(String documentId, InvoiceItemInfo item) {
        requireDraft(documentId);

        jdbc.sql("""
                        INSERT INTO document_item (id, document_id, position, description, quantity_thousandths,
                                                   unit_code, unit_name, unit_name_plural, unit_unece_code,
                                                   unit_price_cents, discount_bp, tax_category, tax_rate_bp,
                                                   line_total_cents, catalog_item_id)
                        VALUES (:id, :documentId, :position, :description, :quantity, :unitCode, :unitName,
                                :unitNamePlural, :unitUnece, :price, :discountBp, :taxCategory, :taxRateBp,
                                :lineTotal, :catalogItemId)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("documentId", documentId)
                .param("position", item.position())
                .param("description", item.description())
                .param("quantity", item.quantityThousandths())
                .param("unitCode", item.unitCode())
                .param("unitName", item.unitName())
                .param("unitNamePlural", item.unitNamePlural())
                .param("unitUnece", item.unitUneceCode())
                .param("price", item.unitPriceCents())
                .param("discountBp", item.discountBp())
                .param("taxCategory", item.taxCategory())
                .param("taxRateBp", item.taxRateBp())
                .param("lineTotal", item.lineTotalCents())
                .param("catalogItemId", item.catalogItemId())
                .update();
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
}
