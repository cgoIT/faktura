package de.cgoit.faktura.document.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import de.cgoit.faktura.document.OfferEvent;
import de.cgoit.faktura.document.OfferEventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and appends the events of offers. The table is insert-only: nothing here updates or deletes a row.
 */
@Repository
public class OfferEventRepository {

    private final JdbcClient jdbc;

    public OfferEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends an event. The table only ever gets new rows.
     */
    public void insert(String id, String documentId, OfferEventType type, LocalDate date, String actor,
            Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO offer_event (id, document_id, event, event_date, actor, created_at)
                        VALUES (:id, :documentId, :event, :date, :actor, :createdAt)
                        """)
                .param("id", id)
                .param("documentId", documentId)
                .param("event", type.name())
                .param("date", date.toString())
                .param("actor", actor)
                .param("createdAt", createdAt.toString())
                .update();
    }

    /**
     * Returns the events of one offer, oldest first.
     */
    public List<OfferEvent> findByDocument(String documentId) {
        return jdbc.sql("""
                        SELECT event, event_date, actor FROM offer_event
                        WHERE document_id = :id ORDER BY created_at, id
                        """)
                .param("id", documentId)
                .query((rs, rowNum) -> new OfferEvent(OfferEventType.valueOf(rs.getString("event")),
                        LocalDate.parse(rs.getString("event_date")), rs.getString("actor")))
                .list();
    }

    /**
     * Returns the events of all offers by document id, each list oldest first.
     */
    public Map<String, List<OfferEvent>> findAllByDocument() {
        return jdbc.sql("SELECT document_id, event, event_date, actor FROM offer_event ORDER BY created_at, id")
                .query((rs, rowNum) -> Map.entry(rs.getString("document_id"),
                        new OfferEvent(OfferEventType.valueOf(rs.getString("event")),
                                LocalDate.parse(rs.getString("event_date")), rs.getString("actor"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }
}
