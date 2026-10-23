package de.cgoit.faktura.document;

import java.time.LocalDate;

/**
 * One recorded event of an offer.
 *
 * @param actor username of the user who caused it, {@code null} if unknown
 */
public record OfferEvent(OfferEventType type, LocalDate date, String actor) {
}
