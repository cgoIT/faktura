package de.cgoit.faktura.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceBalance;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.OfferState;
import de.cgoit.faktura.document.OfferSummary;
import de.cgoit.faktura.document.PaymentState;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.notification.internal.DigestBuilder;
import de.cgoit.faktura.notification.internal.NotificationProperties;
import de.cgoit.faktura.notification.internal.NotificationRepository;
import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.shared.MoneyFormatter;
import de.cgoit.faktura.textblock.Placeholder;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Writing of the sections: the list limit, escaping of values and the link.
 */
class DigestBuilderTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-23");

    private final InvoiceService invoices = mock(InvoiceService.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final OfferService offers = mock(OfferService.class);
    private final RecurringPlanService plans = mock(RecurringPlanService.class);
    private final DunningService dunnings = mock(DunningService.class);
    private final CompanyService company = mock(CompanyService.class);

    private DigestBuilder builder(int maxRows, String baseUrl) {
        var messages = new ResourceBundleMessageSource();

        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        when(repository.reported(any())).thenReturn(Set.of());
        when(company.find()).thenReturn(Optional.<CompanyInfo>empty());

        return new DigestBuilder(plans, invoices, offers, dunnings, company, repository,
                new NotificationProperties(true, Duration.ofMinutes(5), "08:00", Duration.ofHours(1), 5, maxRows),
                new MoneyFormatter(), messages, baseUrl);
    }

    private static InvoiceSummary overdue(int index, String customer) {
        var balance = new InvoiceBalance(PaymentState.OPEN, 100_000, 0, 100_000, 0, true, 10);

        return new InvoiceSummary("id-" + index, DocumentType.INVOICE, DocumentStatus.FINAL, "RE" + index, "c1",
                customer, "Betreff", TODAY.minusDays(24), TODAY.minusDays(10), 100_000, balance, false);
    }

    @Test
    void onlyTheFirstItemsAreListedAndLoggedTheRestFollowsLater() {
        var list = new ArrayList<InvoiceSummary>();

        IntStream.range(0, 25).forEach(i -> list.add(overdue(i, "Muster GmbH")));
        when(invoices.listOverdue()).thenReturn(list);

        var digest = builder(20, "").build(settings(), TODAY);

        assertThat(digest.items()).hasSize(20);
        assertThat(digest.values().get(Placeholder.NOTICE_OVERDUE)).contains("Überfällige Rechnungen (25)")
                .contains("… und 5 weitere");
        assertThat(digest.values().get(Placeholder.NOTICE_COUNT)).isEqualTo("20");
    }

    @Test
    void valuesAreEscapedSoAMarkupCharacterInANameStaysText() {
        when(invoices.listOverdue()).thenReturn(List.of(overdue(1, "Fritz *Muster* _GmbH_")));

        var digest = builder(20, "").build(settings(), TODAY);

        assertThat(digest.values().get(Placeholder.NOTICE_OVERDUE)).contains("Fritz \\*Muster\\* \\_GmbH\\_");
    }

    @Test
    void theLinkIsEmptyWithoutABaseUrl() {
        assertThat(builder(20, "").build(settings(), TODAY).values().get(Placeholder.NOTICE_LINK)).isEmpty();
        assertThat(builder(20, " http://localhost:8090/ ").build(settings(), TODAY).values()
                .get(Placeholder.NOTICE_LINK)).isEqualTo("http://localhost:8090/");
    }

    @Test
    void aSwitchedOffCategoryIsNotEvenCollected() {
        var digest = builder(20, "").build(new NotificationSettings(false, false, false, false, null, null, null, 0),
                TODAY);

        assertThat(digest.isEmpty()).isTrue();
    }

    @Test
    void anExpiringOfferIsListedWithItsValidityAndOnlyWhenTheCategoryIsOn() {
        var offer = new OfferSummary("o1", DocumentStatus.FINAL, OfferState.SENT, "AN202600001", "c1", "Muster GmbH",
                "Relaunch", TODAY.minusDays(20), TODAY.plusDays(3), 119_000);

        when(offers.expiringSoon()).thenReturn(List.of(offer));

        var on = builder(20, "").build(new NotificationSettings(false, false, false, false, true, null, null, null, 0),
                TODAY);
        var off = builder(20, "").build(settings(), TODAY);

        assertThat(on.items()).hasSize(1);
        assertThat(on.items().getFirst().category()).isEqualTo(NotificationCategory.OFFER_EXPIRING);
        assertThat(on.values().get(Placeholder.NOTICE_OFFERS)).contains("Angebot läuft bald ab (1)")
                .contains("AN202600001").contains("26.10.2026");
        assertThat(off.values().get(Placeholder.NOTICE_OFFERS)).isEmpty();
    }

    private static NotificationSettings settings() {
        return new NotificationSettings(false, false, true, false, null, null, null, 0);
    }
}
