package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Without a configured mail function nothing is sent, the pages still work and say why.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.mail.enabled=false", "spring.mail.host=" })
@Import(TestMailConfiguration.class)
@AutoConfigureMockMvc
class MailDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MailService mail;

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

    private MailTarget target;

    @BeforeEach
    void setUp() {
        MailTestData.cleanUp(jdbc);
        sender.reset();
        MailTestData.company(jdbc);

        var ids = MailTestData.customer(customers);
        var invoice = MailTestData.invoice(invoices, finalizer, ids[0], ids[1], LocalDate.now());

        target = new MailTarget(MailOwnerType.DOCUMENT, invoice.id());
    }

    @AfterEach
    void cleanUp() {
        MailTestData.cleanUp(jdbc);
    }

    @Test
    void statusSaysWhySendingIsNotPossible() {
        assertThat(mail.status().available()).isFalse();
        assertThat(mail.status().reasonKey()).isEqualTo("mail.disabled.off");
    }

    @Test
    void draftIsBlockedAndSendingIsRefusedWithoutSendingAnything() {
        assertThat(mail.prepare(target, null).blockedKey()).isEqualTo("mail.disabled.off");
        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, "a@example.test", "Betreff", "Text", true)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.disabled.off");
        assertThat(sender.sent()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_log").query(Integer.class).single()).isZero();
    }

    @Test
    void panelShowsADisabledButtonWithTheReason() throws Exception {
        mvc.perform(get("/mail/panel").param("type", "DOCUMENT").param("id", target.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("disabled")))
                .andExpect(content().string(containsString("Mailversand ist ausgeschaltet")));
    }

    @Test
    void templatePageStillWorksAndExplainsTheState() throws Exception {
        mvc.perform(get("/mail-templates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nicht m")))
                .andExpect(content().string(containsString("Formell")));
    }
}
