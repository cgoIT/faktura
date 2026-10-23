package de.cgoit.faktura.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.mail.CapturingMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class NotificationPagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CapturingMailSender sender;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        NotificationTestData.cleanUp(jdbc);
        sender.reset();
        NotificationTestData.company(jdbc, NotificationTestData.OPERATOR_EMAIL);
    }

    @AfterEach
    void cleanUp() {
        NotificationTestData.cleanUp(jdbc);
        sender.reset();
    }

    @Test
    void theSettingsPageShowsEverythingSwitchedOff() throws Exception {
        mvc.perform(get("/settings/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Benachrichtigungen")))
                .andExpect(content().string(containsString("Neue Entwürfe aus Wiedervorlagen")))
                .andExpect(content().string(containsString("Jetzt prüfen")))
                .andExpect(content().string(containsString("betreiber@example.test")))
                .andExpect(content().string(containsString("08:00")));
    }

    @Test
    void savingStoresTheSettingsAndRedirects() throws Exception {
        mvc.perform(post("/settings/notifications").param("overdueEnabled", "true")
                        .param("draftEnabled", "true").param("recipient", "mich@example.test")
                        .param("sendTime", "07:30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/notifications"))
                .andExpect(flash().attribute("message", "notification.saved"));

        var settings = notifications.settings();

        assertThat(settings.overdueEnabled()).isTrue();
        assertThat(settings.draftEnabled()).isTrue();
        assertThat(settings.planErrorEnabled()).isFalse();
        assertThat(settings.recipient()).isEqualTo("mich@example.test");
        assertThat(settings.sendTime()).isEqualTo("07:30");
    }

    @Test
    void anInvalidAddressOrTimeIsShownAtItsField() throws Exception {
        mvc.perform(post("/settings/notifications").param("recipient", "kein-mail").param("sendTime", "25:99"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kein-mail")));
        mvc.perform(post("/settings/notifications").param("recipient", "kein-mail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("field-error")));
        assertThat(notifications.settings().anyEnabled()).isFalse();
    }

    @Test
    void switchingOnWithoutAnyAddressIsRefusedOnThePage() throws Exception {
        jdbc.sql("UPDATE company SET email = NULL").update();

        mvc.perform(post("/settings/notifications").param("overdueEnabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Empfängeradresse")));
        assertThat(notifications.settings().anyEnabled()).isFalse();
    }

    @Test
    void checkNowSendsOneMailToTheOperatorWhenThereIsSomethingToReport() throws Exception {
        var customerId = NotificationTestData.customer(customers, "Muster GmbH");

        NotificationTestData.invoice(invoices, finalizer, customerId, LocalDate.now().minusDays(30));
        notifications.update(new NotificationInput(false, false, true, false, null, null));

        mvc.perform(post("/settings/notifications/check"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "notification.check.sent"));
        mvc.perform(post("/settings/notifications/check"))
                .andExpect(flash().attribute("message", "notification.check.nothing"));
        assertThat(sender.sent()).hasSize(1);
    }

    @Test
    void checkNowWithEverythingOffSaysSo() throws Exception {
        mvc.perform(post("/settings/notifications/check"))
                .andExpect(flash().attribute("error", "notification.check.disabled"));
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void theTestMailGoesToTheOperator() throws Exception {
        mvc.perform(post("/settings/notifications/test"))
                .andExpect(flash().attribute("message", "notification.test.sent"));
        assertThat(sender.sent()).hasSize(1);
    }

    @Test
    void theTestMailWithoutARecipientShowsTheError() throws Exception {
        jdbc.sql("UPDATE company SET email = NULL").update();

        mvc.perform(post("/settings/notifications/test"))
                .andExpect(flash().attribute("error", "notification.error.noRecipient"));
        assertThat(sender.sent()).isEmpty();
    }
}
