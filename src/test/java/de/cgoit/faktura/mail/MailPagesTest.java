package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class MailPagesTest {

    @Autowired
    private MockMvc mvc;

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

    private InvoiceInfo invoice;

    @BeforeEach
    void setUp() {
        MailTestData.cleanUp(jdbc);
        sender.reset();
        MailTestData.company(jdbc);

        var ids = MailTestData.customer(customers);

        invoice = MailTestData.invoice(invoices, finalizer, ids[0], ids[1], LocalDate.now());
    }

    @AfterEach
    void cleanUp() {
        MailTestData.cleanUp(jdbc);
        sender.reset();
    }

    @Test
    void templateListShowsTheVariantsPerDocumentType() throws Exception {
        mvc.perform(get("/mail-templates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Formell")))
                .andExpect(content().string(containsString("Informell")))
                .andExpect(content().string(containsString("Neue Mailvorlage")))
                .andExpect(content().string(containsString("Standard für Mahnungen")));
    }

    @Test
    void newTemplateFormHasToolbarPlaceholderHelpAndLivePreview() throws Exception {
        mvc.perform(get("/mail-templates/new").param("scope", "INVOICE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-format-target=\"body\"")))
                .andExpect(content().string(containsString("{mahnungsnummer}")))
                .andExpect(content().string(containsString("hx-post=\"/mail-templates/preview\"")))
                .andExpect(content().string(containsString(invoice.number())));
    }

    @Test
    void creatingUpdatingAndDeletingATemplateWorks() throws Exception {
        mvc.perform(post("/mail-templates").param("scope", "INVOICE").param("name", "Kurz")
                        .param("subject", "Rechnung {rechnungsnummer}").param("body", "{anrede}\n\nanbei die Rechnung."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mail-templates"))
                .andExpect(flash().attribute("message", "mail.template.created"));

        var id = jdbc.sql("SELECT id FROM mail_template WHERE name = 'Kurz'").query(String.class).single();

        mvc.perform(post("/mail-templates/" + id).param("scope", "INVOICE").param("name", "Kürzer")
                        .param("subject", "Neu").param("body", "Text").param("makeDefault", "true"))
                .andExpect(redirectedUrl("/mail-templates"))
                .andExpect(flash().attribute("message", "mail.template.saved"));
        assertThat(jdbc.sql("SELECT is_default FROM mail_template WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/mail-templates/" + id + "/delete"))
                .andExpect(redirectedUrl("/mail-templates"))
                .andExpect(flash().attribute("message", "mail.template.deleted"));
        mvc.perform(get("/mail-templates/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void unknownPlaceholderIsReportedAtTheForm() throws Exception {
        mvc.perform(post("/mail-templates").param("scope", "INVOICE").param("name", "Kaputt")
                        .param("subject", "Hallo {gibtsnicht}").param("body", "Text"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unbekannten Platzhalter")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_template WHERE name = 'Kaputt'").query(Integer.class).single())
                .isZero();
    }

    @Test
    void templateListShowsTheDigestTemplateAsSystemTemplate() throws Exception {
        mvc.perform(get("/mail-templates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hinweise an den Betreiber")));
    }

    @Test
    void digestTemplateFormHasNoDeleteCopyOrDefaultAndOffersTheDigestPlaceholders() throws Exception {
        var id = jdbc.sql("SELECT id FROM mail_template WHERE scope = 'NOTIFICATION_DIGEST'")
                .query(String.class)
                .single();

        mvc.perform(get("/mail-templates/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("{entwuerfe}")))
                .andExpect(content().string(containsString("{link}")))
                .andExpect(content().string(not(containsString("{rechnungsnummer}"))))
                .andExpect(content().string(not(containsString("/delete"))))
                .andExpect(content().string(not(containsString("makeDefault"))));
    }

    @Test
    void digestPreviewShowsTheSampleSections() throws Exception {
        mvc.perform(post("/mail-templates/preview").param("subject", "faktura: {anzahl} Hinweise")
                        .param("body", "{entwuerfe}\n\n{ueberfaellig}").param("source", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("faktura: 4 Hinweise")))
                .andExpect(content().string(containsString("Neue Entwürfe aus Wiedervorlagen (1)")))
                .andExpect(content().string(containsString("Überfällige Rechnungen (1)")));
    }

    @Test
    void previewWithSampleValuesShowsSubjectHtmlTextAndValuesAndSavesNothing() throws Exception {
        mvc.perform(post("/mail-templates/preview").param("subject", "Rechnung {rechnungsnummer}")
                        .param("body", "Hallo **{kunde}**").param("source", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rechnung RE202600001")))
                .andExpect(content().string(containsString("sandbox")))
                .andExpect(content().string(containsString("srcdoc=")))
                .andExpect(content().string(containsString("Beispiel GmbH")))
                .andExpect(content().string(containsString("{rechnungsnummer}")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_template").query(Integer.class).single()).isEqualTo(9);
    }

    @Test
    void previewWithADocumentUsesItsValues() throws Exception {
        mvc.perform(post("/mail-templates/preview").param("subject", "Rechnung {rechnungsnummer}")
                        .param("body", "{kunde}").param("source", "DOCUMENT:" + invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rechnung " + invoice.number())))
                .andExpect(content().string(containsString("Muster GmbH")));
    }

    @Test
    void previewReportsUnknownPlaceholders() throws Exception {
        mvc.perform(post("/mail-templates/preview").param("subject", "Betreff").param("body", "{gibtsnicht}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unbekannten Platzhalter")))
                .andExpect(content().string(not(containsString("srcdoc="))));
    }

    @Test
    void invoicePageLoadsTheMailPanel() throws Exception {
        mvc.perform(get("/invoices/" + invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/mail/panel?type=DOCUMENT&amp;id=" + invoice.id())));
    }

    @Test
    void panelOffersTheSendButtonAndAnEmptyDialog() throws Exception {
        mvc.perform(get("/mail/panel").param("type", "DOCUMENT").param("id", invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/mail/compose?type=DOCUMENT&amp;id=" + invoice.id())))
                .andExpect(content().string(containsString("mail-dialog-" + invoice.id())))
                .andExpect(content().string(not(containsString("disabled"))));
    }

    @Test
    void composeDialogShowsRecipientTextAttachmentAndLocksTheSendButton() throws Exception {
        mvc.perform(get("/mail/compose").param("type", "DOCUMENT").param("id", invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MailTestData.CONTACT_EMAIL)))
                .andExpect(content().string(containsString("Rechnung " + invoice.number() + ".pdf")))
                .andExpect(content().string(containsString("Sehr geehrte Frau Beispiel")))
                .andExpect(content().string(containsString("data-format-target=\"mail-body\"")))
                .andExpect(content().string(containsString("id=\"mail-submit\" disabled")))
                .andExpect(content().string(containsString("Informell")));
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void composeForADraftShowsAnErrorInsteadOfAForm() throws Exception {
        mvc.perform(get("/mail/compose").param("type", "DOCUMENT").param("id", "unbekannt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nur finalisierte Belege")))
                .andExpect(content().string(not(containsString("mail-submit"))));
    }

    @Test
    void sendingNeedsTheConfirmationAndThenShowsTheResultAndRefreshesTheHistory() throws Exception {
        mvc.perform(post("/mail/send").param("type", "DOCUMENT").param("id", invoice.id())
                        .param("recipients", MailTestData.CONTACT_EMAIL).param("subject", "Betreff")
                        .param("body", "Text"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte best")))
                .andExpect(header().doesNotExist("HX-Trigger"));
        assertThat(sender.sent()).isEmpty();

        mvc.perform(post("/mail/send").param("type", "DOCUMENT").param("id", invoice.id())
                        .param("recipients", MailTestData.CONTACT_EMAIL).param("subject", "Betreff")
                        .param("body", "Text").param("confirmed", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "mailSent"))
                .andExpect(content().string(containsString("gesendet")));
        assertThat(sender.sent()).hasSize(1);

        mvc.perform(get("/mail/history").param("type", "DOCUMENT").param("id", invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zuletzt gesendet am")))
                .andExpect(content().string(containsString(MailTestData.CONTACT_EMAIL)));
    }

    @Test
    void smtpFailureIsShownInTheDialogAndInTheHistory() throws Exception {
        sender.failWith(new MailSendException("boom"));

        mvc.perform(post("/mail/send").param("type", "DOCUMENT").param("id", invoice.id())
                        .param("recipients", MailTestData.CONTACT_EMAIL).param("subject", "Betreff")
                        .param("body", "Text").param("confirmed", "true"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("HX-Trigger"))
                .andExpect(content().string(containsString("nicht gesendet werden")))
                .andExpect(content().string(containsString("Betreff")));

        mvc.perform(get("/mail/history").param("type", "DOCUMENT").param("id", invoice.id()))
                .andExpect(content().string(containsString("fehlgeschlagen")))
                .andExpect(content().string(not(containsString("boom"))));
    }

    @Test
    void previewEndpointRendersTheTypedText() throws Exception {
        mvc.perform(post("/mail/preview").param("subject", "Betreff").param("body", "**fett**"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;fett&lt;/b&gt;")));
    }
}
