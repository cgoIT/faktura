package de.cgoit.faktura.document;

import static de.cgoit.faktura.shared.Texts.contains;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.document.internal.OfferEventRepository;
import de.cgoit.faktura.document.internal.OfferProperties;
import de.cgoit.faktura.document.internal.OfferStateCalculator;
import de.cgoit.faktura.document.internal.SnapshotCodec;
import de.cgoit.faktura.shared.CurrentUser;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and tracks offers: the list, the derived state, the recorded events and the invoices created from an
 * offer. An offer is a document of type {@code OFFER}; drafts are created and
 * finalized through {@link InvoiceService} and {@link FinalizeService}. Its course (sent, accepted, rejected) is
 * recorded as events, so a finalized offer itself is never changed.
 */
@Service
public class OfferService {

    private final DocumentRepository documents;
    private final OfferEventRepository events;
    private final CustomerService customers;
    private final SnapshotCodec codec;
    private final InvoiceService invoices;
    private final TextTemplateService texts;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final OfferProperties properties;
    private final Clock clock;

    public OfferService(DocumentRepository documents, OfferEventRepository events, CustomerService customers,
            SnapshotCodec codec, InvoiceService invoices, TextTemplateService texts, AuditService audit,
            CurrentUser currentUser, OfferProperties properties, Clock clock) {
        this.documents = documents;
        this.events = events;
        this.customers = customers;
        this.codec = codec;
        this.invoices = invoices;
        this.texts = texts;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Lists offers, newest first, whose number, subject or customer name contains the search text (case-insensitive,
     * German rules).
     *
     * @param query search text, blank for all
     * @param state only offers in this state, {@code null} for all
     */
    @Transactional(readOnly = true)
    public List<OfferSummary> list(String query, OfferState state) {
        var needle = query == null ? "" : query.strip()
                .toLowerCase(Locale.GERMAN);
        var names = customers.search("", CustomerStatus.ALL).stream()
                .collect(Collectors.toMap(CustomerInfo::id, CustomerInfo::name));
        var eventsByOffer = events.findAllByDocument();
        var today = LocalDate.now(clock);

        return documents.findAll(DocumentType.OFFER).stream()
                .map(header -> toSummary(header, names, eventsByOffer.getOrDefault(header.id(), List.of()), today))
                .filter(row -> state == null || row.state() == state)
                .filter(row -> needle.isEmpty()
                        || contains(row.number(), needle)
                        || contains(row.customerName(), needle)
                        || contains(row.subject(), needle))
                .toList();
    }

    /**
     * Lists the offers that are still valid, have no decision yet and expire within the configured number of days
     * ({@code faktura.offer.expiring-days}, today included), the one that expires first on top.
     */
    @Transactional(readOnly = true)
    public List<OfferSummary> expiringSoon() {
        var limit = LocalDate.now(clock).plusDays(properties.expiringDays());

        return list("", null).stream()
                .filter(row -> row.state() == OfferState.OPEN || row.state() == OfferState.SENT)
                .filter(row -> row.validUntil() != null && !row.validUntil().isAfter(limit))
                .sorted(Comparator.comparing(OfferSummary::validUntil).thenComparing(OfferSummary::id))
                .toList();
    }

    /**
     * Returns the number of days before the end of its validity an offer counts as expiring soon.
     */
    public int expiringDays() {
        return properties.expiringDays();
    }

    /**
     * Returns the derived state of an offer, empty if the document is unknown or not an offer.
     */
    @Transactional(readOnly = true)
    public Optional<OfferState> state(String offerId) {
        return documents.findById(offerId)
                .filter(header -> header.type() == DocumentType.OFFER)
                .map(header -> OfferStateCalculator.state(header.status(), header.validUntil(),
                        events.findByDocument(offerId), LocalDate.now(clock)));
    }

    /**
     * Returns the recorded events of an offer, oldest first.
     */
    @Transactional(readOnly = true)
    public List<OfferEvent> events(String offerId) {
        return events.findByDocument(offerId);
    }

    /**
     * Records an event of a finalized offer, dated today and with the current user as actor. {@code SENT} may repeat
     * (the offer can be sent again); a decision ({@code ACCEPTED}, {@code REJECTED}) is refused when the offer is
     * already decided the same way. The offer itself is never changed: the event is a new row.
     *
     * <p>The check and the insert are not one atomic step. A double click could record a decision twice; that is
     * harmless, because the state is derived from the latest decision, and the application has few users.
     *
     * @throws RuleViolationException if the document is no finalized offer or the decision was already recorded
     */
    @Transactional
    public OfferEvent record(String offerId, OfferEventType type) {
        var header = requireFinalOffer(offerId);
        var existing = events.findByDocument(offerId);
        var state = OfferStateCalculator.state(header.status(), header.validUntil(), existing, LocalDate.now(clock));

        if (type == OfferEventType.ACCEPTED && state == OfferState.ACCEPTED
                || type == OfferEventType.REJECTED && state == OfferState.REJECTED) {
            throw new RuleViolationException("offer.error.alreadyDecided");
        }

        var today = LocalDate.now(clock);
        var actor = currentUser.username();

        events.insert(UUID.randomUUID().toString(), offerId, type, today, actor, Instant.now(clock));

        if (type != OfferEventType.SENT) {
            audit.record(AuditObjectType.DOCUMENT, offerId, AuditAction.OFFER_EVENT,
                    Map.of("number", header.number(), "event", type.name()));
        }

        return new OfferEvent(type, today, actor);
    }

    /**
     * Creates an invoice draft from a finalized offer: customer, contact person, customer reference, items,
     * discounts, shipping costs and subject are copied; header and footer text are the defaults for invoices, because
     * the offer's text speaks of an offer. The draft refers to the offer, which stays unchanged. The offer counts as
     * accepted from now on, unless it already does. Prices and tax are checked against the tax profile of today, as
     * for any draft; finalizing takes the profile of the service day.
     *
     * @throws RuleViolationException if the document is no finalized offer or the draft is invalid
     */
    @Transactional
    public InvoiceInfo createInvoiceDraft(String offerId) {
        var header = requireFinalOffer(offerId);
        var offer = invoices.find(offerId)
                .orElseThrow();
        var inputItems = offer.items().stream()
                .map(item -> new InvoiceItemInput(item.description(), item.quantityThousandths(), item.unitCode(),
                        item.unitPriceCents(), item.discountBp(), item.taxCategory(), item.taxRateBp(),
                        item.catalogItemId()))
                .toList();
        var shipping = offer.shipping().isPresent() ? offer.shipping() : null;
        var input = new InvoiceInput(offer.customerId(), LocalDate.now(clock), offer.serviceFrom(), offer.serviceTo(),
                null, offer.subject(), texts.defaultBody(TextScope.INVOICE, TextKind.HEADER).orElse(null),
                texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER).orElse(null), offer.totalDiscount(), inputItems,
                offer.contactId(), offer.customerReference(), shipping);
        var draft = invoices.createInvoiceDraftForOffer(offerId, input);
        var state = state(offerId).orElseThrow();

        if (state != OfferState.ACCEPTED) {
            record(offerId, OfferEventType.ACCEPTED);
        }

        return draft;
    }

    /**
     * Returns the ids of the invoices created from an offer, oldest first.
     */
    @Transactional(readOnly = true)
    public List<String> invoiceIds(String offerId) {
        return documents.findInvoiceIdsOfOffer(offerId);
    }

    private Header requireFinalOffer(String offerId) {
        return documents.findById(offerId)
                .filter(header -> header.type() == DocumentType.OFFER && header.status() == DocumentStatus.FINAL)
                .orElseThrow(() -> new RuleViolationException("offer.error.notFinal"));
    }

    private OfferSummary toSummary(Header header, Map<String, String> names, List<OfferEvent> offerEvents,
            LocalDate today) {
        var customerName = header.recipientSnapshot() != null
                ? codec.recipient(header.recipientSnapshot()).name()
                : names.getOrDefault(header.customerId(), "");

        return new OfferSummary(header.id(), header.status(),
                OfferStateCalculator.state(header.status(), header.validUntil(), offerEvents, today), header.number(),
                header.customerId(), customerName, header.subject(), header.issueDate(), header.validUntil(),
                header.grossTotalCents());
    }
}
