package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.document.internal.OfferStateCalculator;
import org.junit.jupiter.api.Test;

class OfferStateCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private static OfferEvent event(OfferEventType type) {
        return new OfferEvent(type, TODAY.minusDays(3), "anonymous");
    }

    private static OfferState state(DocumentStatus status, LocalDate validUntil, OfferEvent... events) {
        return OfferStateCalculator.state(status, validUntil, List.of(events), TODAY);
    }

    @Test
    void aDraftStaysADraftWhateverElseIsSet() {
        assertThat(state(DocumentStatus.DRAFT, TODAY.minusDays(30))).isEqualTo(OfferState.DRAFT);
    }

    @Test
    void aFinalOfferWithoutEventsIsOpenUntilItsLastValidDay() {
        assertThat(state(DocumentStatus.FINAL, TODAY)).isEqualTo(OfferState.OPEN);
        assertThat(state(DocumentStatus.FINAL, TODAY.minusDays(1))).isEqualTo(OfferState.EXPIRED);
    }

    @Test
    void sendingMakesItSentButExpiryOutranksIt() {
        assertThat(state(DocumentStatus.FINAL, TODAY.plusDays(5), event(OfferEventType.SENT)))
                .isEqualTo(OfferState.SENT);
        assertThat(state(DocumentStatus.FINAL, TODAY.minusDays(1), event(OfferEventType.SENT)))
                .isEqualTo(OfferState.EXPIRED);
    }

    @Test
    void aDecisionOutranksExpiryAndTheLatestDecisionCounts() {
        assertThat(state(DocumentStatus.FINAL, TODAY.minusDays(10), event(OfferEventType.SENT),
                event(OfferEventType.ACCEPTED))).isEqualTo(OfferState.ACCEPTED);
        assertThat(state(DocumentStatus.FINAL, TODAY.plusDays(10), event(OfferEventType.REJECTED)))
                .isEqualTo(OfferState.REJECTED);
        assertThat(state(DocumentStatus.FINAL, TODAY.plusDays(10), event(OfferEventType.REJECTED),
                event(OfferEventType.ACCEPTED))).isEqualTo(OfferState.ACCEPTED);
    }
}
