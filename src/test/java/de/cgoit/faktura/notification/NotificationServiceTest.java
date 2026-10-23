package de.cgoit.faktura.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.OfferEventType;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.mail.CapturingMailSender;
import de.cgoit.faktura.notification.DigestResult.Outcome;
import de.cgoit.faktura.recurring.PlanInput;
import de.cgoit.faktura.recurring.PlanItemInput;
import de.cgoit.faktura.recurring.RecurringInterval;
import de.cgoit.faktura.recurring.RecurringJobService;
import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.recurring.RecurringStatus;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;

@SqliteTest
class NotificationServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private NotificationService notifications;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private CustomerService customers;

    @Autowired
    private OfferService offers;

    @Autowired
    private RecurringPlanService plans;

    @Autowired
    private RecurringJobService job;

    @Autowired
    private CapturingMailSender sender;

    @Autowired
    private JdbcClient jdbc;

    private String customerId;

    @BeforeEach
    void setUp() {
        NotificationTestData.cleanUp(jdbc);
        sender.reset();
        NotificationTestData.company(jdbc, NotificationTestData.OPERATOR_EMAIL);
        customerId = NotificationTestData.customer(customers, "Muster GmbH");
    }

    @AfterEach
    void cleanUp() {
        NotificationTestData.cleanUp(jdbc);
        sender.reset();
    }

    private void enableAll() {
        notifications.update(new NotificationInput(true, true, true, true, null, null));
    }

    private void enableOnly(boolean draft, boolean planError, boolean overdue, boolean dunning) {
        notifications.update(new NotificationInput(draft, planError, overdue, dunning, null, null));
    }

    private String invoiceDueDaysAgo(int days) {
        return NotificationTestData.invoice(invoices, finalizer, customerId, TODAY.minusDays(14L + days))
                .id();
    }

    private String planWithDraft(String name) {
        var item = new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null);
        var plan = plans.create(new PlanInput(customerId, name, RecurringInterval.QUARTER, TODAY.plusDays(5), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, List.of(item)));

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0 WHERE id = :id")
                .param("id", plan.id())
                .update();
        job.runDue(TODAY);

        return plan.id();
    }

    private String planWithError(String name) {
        var item = new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null);
        var plan = plans.create(new PlanInput(customerId, name, RecurringInterval.YEAR, TODAY.plusDays(200), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null, List.of(item)));

        jdbc.sql("UPDATE recurring_plan SET last_error = 'recurring.error.unexpected' WHERE id = :id")
                .param("id", plan.id())
                .update();

        return plan.id();
    }

    private String mailText(int index) throws Exception {
        return NotificationTestData.text(sender.sent().get(index));
    }

    private long logged(String category) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_log WHERE category = :category")
                .param("category", category)
                .query(Long.class)
                .single();
    }

    @Test
    void everythingIsOffByDefaultAndNothingIsSent() {
        invoiceDueDaysAgo(30);

        var result = notifications.runNow();

        assertThat(result.outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(sender.sent()).isEmpty();
        assertThat(notifications.settings().anyEnabled()).isFalse();
    }

    @Test
    void oneMailCarriesAllCategoriesAsSections() throws Exception {
        invoiceDueDaysAgo(26);
        planWithDraft("Wartungsvertrag");
        planWithError("Hostingvertrag");
        enableAll();

        var result = notifications.runNow();

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(result.count()).isEqualTo(4);
        assertThat(sender.sent()).hasSize(1);

        var message = sender.sent().getFirst();
        var text = mailText(0);

        assertThat(message.getSubject()).isEqualTo("faktura: 4 Hinweise");
        assertThat(text).contains("Neue Entwürfe aus Wiedervorlagen (1)", "Wartungsvertrag", "Muster GmbH")
                .contains("Fehler in Wiedervorlagen (1)", "Hostingvertrag")
                .contains("Überfällige Rechnungen (1)", "26 Tage überfällig")
                .contains("Mahnung fällig (1)", "Zahlungserinnerung");
    }

    @Test
    void theMailGoesToTheOperatorOnlyNeverToTheCustomer() {
        invoiceDueDaysAgo(5);
        enableAll();

        notifications.runNow();

        assertThat(sender.sent()).hasSize(1);
        assertThat(recipients()).containsExactly(NotificationTestData.OPERATOR_EMAIL)
                .doesNotContain(NotificationTestData.CUSTOMER_EMAIL);
    }

    private List<String> recipients() {
        return sender.sent()
                .stream()
                .flatMap(message -> {
                    try {
                        return Arrays.stream(message.getAllRecipients());
                    } catch (MessagingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(Object::toString)
                .toList();
    }

    @Test
    void aChosenRecipientReplacesTheAddressOfTheCompany() {
        notifications.update(new NotificationInput(false, false, true, false, "  anderer@example.test ", null));
        invoiceDueDaysAgo(5);

        notifications.runNow();

        assertThat(recipients()).containsExactly("anderer@example.test");
    }

    @Test
    void aSecondRunReportsNothingAgain() {
        invoiceDueDaysAgo(5);
        planWithDraft("Wartungsvertrag");
        enableAll();

        notifications.runNow();
        var second = notifications.runNow();

        assertThat(second.outcome()).isEqualTo(Outcome.NOTHING_TO_REPORT);
        assertThat(sender.sent()).hasSize(1);
        assertThat(logged("OVERDUE")).isEqualTo(1);
        assertThat(logged("DRAFT")).isEqualTo(1);
    }

    private String offerEndingInDays(int days) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createOfferDraft(new InvoiceInput(customerId, TODAY, null, null, null, "Betreff", "Kopf",
                "Fuß", TotalDiscount.none(), List.of(item), null, null, null, TODAY.plusDays(days)));

        return finalizer.finalizeInvoice(draft.id())
                .id();
    }

    @Test
    void anOfferThatExpiresSoonIsReportedOnceAndOnlyWithoutADecision() throws Exception {
        var expiring = offerEndingInDays(3);
        offerEndingInDays(30);
        var decided = offerEndingInDays(2);

        offers.record(decided, OfferEventType.REJECTED);
        notifications.update(new NotificationInput(false, false, false, false, true, null, null));

        var first = notifications.runNow();

        assertThat(first.outcome()).isEqualTo(Outcome.SENT);
        assertThat(mailText(0)).contains("Angebot läuft bald ab (1)");
        assertThat(logged("OFFER_EXPIRING")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT object_id FROM notification_log").query(String.class).single())
                .isEqualTo(expiring);
        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.NOTHING_TO_REPORT);
    }

    @Test
    void nothingToReportSendsNoMail() {
        enableAll();

        var result = notifications.runNow();

        assertThat(result.outcome()).isEqualTo(Outcome.NOTHING_TO_REPORT);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void aSwitchedOffCategoryIsNotReported() throws Exception {
        invoiceDueDaysAgo(5);
        planWithDraft("Wartungsvertrag");
        enableOnly(false, false, true, false);

        notifications.runNow();

        assertThat(mailText(0)).contains("Überfällige Rechnungen")
                .doesNotContain("Entwürfe");
        assertThat(logged("DRAFT")).isZero();
    }

    @Test
    void anInvoiceIsOverdueFromTheDayAfterTheDueDate() {
        invoiceDueDaysAgo(0);
        enableOnly(false, false, true, false);

        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.NOTHING_TO_REPORT);

        invoiceDueDaysAgo(1);

        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.SENT);
        assertThat(logged("OVERDUE")).isEqualTo(1);
    }

    @Test
    void partiallyPaidStaysPaidAndCancelledAreLeftOut() {
        var partial = invoiceDueDaysAgo(3);
        var paid = invoiceDueDaysAgo(4);
        var cancelled = invoiceDueDaysAgo(5);

        payments.record(partial, new PaymentInput(TODAY, 40_000, PaymentMethod.BANK_TRANSFER, null, null));
        payments.record(paid, new PaymentInput(TODAY, 100_000, PaymentMethod.BANK_TRANSFER, null, null));
        finalizer.cancelInvoice(cancelled);
        enableOnly(false, false, true, false);

        var result = notifications.runNow();

        assertThat(result.count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT object_id FROM notification_log").query(String.class).single())
                .isEqualTo(partial);
    }

    @Test
    void aPaymentAfterTheReportCausesNoFurtherMail() {
        var id = invoiceDueDaysAgo(3);

        enableOnly(false, false, true, false);
        notifications.runNow();
        payments.record(id, new PaymentInput(TODAY, 100_000, PaymentMethod.BANK_TRANSFER, null, null));

        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.NOTHING_TO_REPORT);
        assertThat(sender.sent()).hasSize(1);
    }

    @Test
    void aDunningLevelIsReportedOncePerLevel() {
        var id = invoiceDueDaysAgo(26);

        enableOnly(false, false, false, true);
        notifications.runNow();
        notifications.runNow();

        assertThat(sender.sent()).hasSize(1);
        assertThat(jdbc.sql("SELECT stage FROM notification_log WHERE object_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("1");
    }

    @Test
    void aFailedMailLeavesTheItemsUnreportedAndTheNextRunSendsThemOnce() {
        invoiceDueDaysAgo(5);
        planWithDraft("Wartungsvertrag");
        enableAll();
        var runsBefore = jdbc.sql("SELECT COUNT(*) FROM recurring_run").query(Long.class).single();
        var indexBefore = jdbc.sql("SELECT next_period_index FROM recurring_plan").query(Integer.class).single();

        sender.failWith(new MailSendException("boom"));
        var failed = notifications.runNow();

        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failed.errorKey()).isEqualTo("mail.error.smtp");
        assertThat(logged("OVERDUE") + logged("DRAFT")).isZero();
        assertThat(notifications.settings().failureCount()).isEqualTo(1);

        sender.reset();
        var retry = notifications.runNow();

        assertThat(retry.outcome()).isEqualTo(Outcome.SENT);
        assertThat(sender.sent()).hasSize(1);
        assertThat(notifications.settings().failureCount()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM recurring_run").query(Long.class).single()).isEqualTo(runsBefore);
        assertThat(jdbc.sql("SELECT next_period_index FROM recurring_plan").query(Integer.class).single())
                .isEqualTo(indexBefore);
    }

    @Test
    void theAutomaticRunPausesAfterRepeatedFailuresAndTheButtonStillWorks() {
        invoiceDueDaysAgo(5);
        enableOnly(false, false, true, false);
        jdbc.sql("UPDATE notification_setting SET failure_count = 5").update();

        assertThat(notifications.runIfDue(TODAY.atTime(23, 59)).outcome()).isEqualTo(Outcome.PAUSED);
        assertThat(sender.sent()).isEmpty();
        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.SENT);
        assertThat(notifications.settings().failureCount()).isZero();
    }

    @Test
    void savingTheSettingsStartsAPausedRunAgain() {
        enableOnly(false, false, true, false);
        jdbc.sql("UPDATE notification_setting SET failure_count = 5").update();

        notifications.update(new NotificationInput(false, false, true, false, null, null));

        assertThat(notifications.settings().failureCount()).isZero();
    }

    @Test
    void theScheduledRunWaitsForTheSendTimeAndRunsOncePerDay() {
        invoiceDueDaysAgo(5);
        notifications.update(new NotificationInput(false, false, true, false, null, "09:30"));

        assertThat(notifications.runIfDue(TODAY.atTime(9, 29))).isNull();
        assertThat(sender.sent()).isEmpty();

        var first = notifications.runIfDue(TODAY.atTime(9, 30));

        assertThat(first.outcome()).isEqualTo(Outcome.SENT);
        assertThat(notifications.runIfDue(TODAY.atTime(15, 0))).isNull();
        assertThat(notifications.runIfDue(TODAY.plusDays(1).atTime(9, 30)).outcome())
                .isEqualTo(Outcome.NOTHING_TO_REPORT);
        assertThat(sender.sent()).hasSize(1);
    }

    @Test
    void theDefaultSendTimeComesFromTheConfiguration() {
        assertThat(notifications.sendTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void withoutMailSetupTheRunIsBlockedWithoutCountingAFailure() {
        invoiceDueDaysAgo(5);
        enableOnly(false, false, true, false);
        jdbc.sql("UPDATE company SET email = NULL").update();
        jdbc.sql("UPDATE notification_setting SET recipient = NULL").update();

        var result = notifications.runNow();

        assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(result.errorKey()).isEqualTo("notification.error.noRecipient");
        assertThat(notifications.settings().failureCount()).isZero();
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void switchingOnWithoutAnyAddressIsRefused() {
        jdbc.sql("UPDATE company SET email = NULL").update();

        assertThatThrownBy(() -> notifications.update(new NotificationInput(true, false, false, false, null, null)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("notification.error.noRecipient");
    }

    @Test
    void invalidAddressesAndTimesAreRefused() {
        assertThatThrownBy(() -> notifications.update(new NotificationInput(false, false, false, false, "kein-mail",
                null))).hasMessage("mail.error.recipients");
        assertThatThrownBy(() -> notifications.update(new NotificationInput(false, false, false, false,
                "a@example.test, b@example.test", null))).hasMessage("mail.error.recipients");
        assertThatThrownBy(() -> notifications.update(new NotificationInput(false, false, false, false, null,
                "25:00"))).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void theTestMailGoesToTheOperatorAndIsNotLogged() {
        enableOnly(false, false, true, false);

        notifications.sendTest();

        assertThat(recipients()).containsExactly(NotificationTestData.OPERATOR_EMAIL);
        assertThat(logged("OVERDUE")).isZero();
    }

    @Test
    void theLogIsUniquePerCategoryObjectAndStage() {
        jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l1', 'OVERDUE', 'd1', '0', '2026-10-23T00:00:00Z')").update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l2', 'OVERDUE', 'd1', '0', '2026-10-24T00:00:00Z')").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(logged("OVERDUE")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification_log (id, category, object_id, stage, sent_at) "
                + "VALUES ('l3', 'OTHER', 'd1', '0', '2026-10-24T00:00:00Z')").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theDateOfAScheduledRunIsRememberedButNotThatOfTheButton() {
        invoiceDueDaysAgo(5);
        enableOnly(false, false, true, false);

        notifications.runNow();

        assertThat(notifications.settings().lastRunDate()).isNull();

        notifications.runIfDue(LocalDateTime.now().with(LocalTime.of(23, 59)));

        assertThat(notifications.settings().lastRunDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void afterAFailureTheScheduledRunWaitsForTheRetryDelay() {
        invoiceDueDaysAgo(5);
        enableOnly(false, false, true, false);
        sender.failWith(new MailSendException("boom"));

        var failed = notifications.runIfDue(TODAY.atTime(23, 59));

        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);

        sender.reset();

        assertThat(notifications.runIfDue(TODAY.atTime(23, 59))).isNull();
        assertThat(sender.sent()).isEmpty();
        assertThat(notifications.runNow().outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void onlyOneRunAtATimeSendsTheItems() throws Exception {
        invoiceDueDaysAgo(5);
        enableOnly(false, false, true, false);

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        sender.beforeSend(message -> {
            started.countDown();

            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(notifications::runNow);

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            var second = notifications.runNow();

            assertThat(second.outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(second.errorKey()).isEqualTo("notification.error.running");

            release.countDown();

            assertThat(first.get().outcome()).isEqualTo(Outcome.SENT);
        }

        assertThat(sender.sent()).hasSize(1);
    }
}
