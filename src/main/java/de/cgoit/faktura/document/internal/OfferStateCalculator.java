package de.cgoit.faktura.document.internal;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.OfferEvent;
import de.cgoit.faktura.document.OfferEventType;
import de.cgoit.faktura.document.OfferState;

/**
 * The rules that turn status, validity date and events of an offer into its state. A decision (accepted or rejected)
 * outranks everything, the latest one counts; without a decision an offer past its validity date is expired, else it is
 * sent if it was sent, else open.
 */
public final class OfferStateCalculator {

    private OfferStateCalculator() {
    }

    /**
     * Derives the state of an offer.
     *
     * @param events the events of the offer, oldest first
     * @param today  the day to judge the validity by; an offer is still valid on its last day
     */
    public static OfferState state(DocumentStatus status, LocalDate validUntil, List<OfferEvent> events,
            LocalDate today) {
        if (status == DocumentStatus.DRAFT) {
            return OfferState.DRAFT;
        }

        for (var index = events.size() - 1; index >= 0; index--) {
            var type = events.get(index).type();

            if (type == OfferEventType.ACCEPTED) {
                return OfferState.ACCEPTED;
            }

            if (type == OfferEventType.REJECTED) {
                return OfferState.REJECTED;
            }
        }

        if (validUntil != null && validUntil.isBefore(today)) {
            return OfferState.EXPIRED;
        }

        var sent = events.stream()
                .anyMatch(event -> event.type() == OfferEventType.SENT);

        return sent ? OfferState.SENT : OfferState.OPEN;
    }
}
