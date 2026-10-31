package de.cgoit.faktura.dunning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.document.TotalDiscount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class DunningPagesTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private DunningLevelService levels;

    private String customerId;

    private List<DunningLevelInfo> originalLevels;

    @BeforeEach
    void setUp() {
        cleanUp();
        originalLevels = levels.list();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E')
                """).update();
        customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null)).id();
    }

    @AfterEach
    void cleanUp() {
        if (originalLevels != null) {
            originalLevels.forEach(level -> levels.update(level.id(), new DunningLevelInput(level.level(), level.name(),
                    level.daysAfterDue(), level.feeCents(), level.interestBp(), level.newDueDays(), level.headerText(),
                    level.footerText(), level.active())));
            jdbc.sql("DELETE FROM dunning_level WHERE level > 3").update();
        }

        TestArchive.clear();
        jdbc.sql("DELETE FROM dunning").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
    }

    private InvoiceInfo invoice(int daysAgo) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY.minusDays(daysAgo), null, null, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.none(), List.of(item)));

        return finalizer.finalizeInvoice(draft.id());
    }

    private String firstLevelId() {
        return levels.list().getFirst().id();
    }

    @Test
    void overviewListsSuggestionsAndTheNavigationEntryIsActive() throws Exception {
        var old = invoice(30);

        var html = mvc.perform(get("/dunning"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahlungserinnerung")))
                .andExpect(content().string(containsString("1.000,00")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(old.number()).contains("href=\"/dunning\"").doesNotContain("https://");
    }

    @Test
    void previewIsAPdfAndCreatesNothing() throws Exception {
        var old = invoice(30);

        mvc.perform(get("/dunning/preview.pdf").param("invoiceId", old.id()).param("levelId", firstLevelId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dunning").query(Long.class).single()).isZero();
    }

    @Test
    void previewForAnInvoiceThatIsNotDueIsRefused() throws Exception {
        var young = invoice(0);

        mvc.perform(get("/dunning/preview.pdf").param("invoiceId", young.id()).param("levelId", firstLevelId()))
                .andExpect(status().isConflict());
    }

    @Test
    void creatingRedirectsAndTheLetterCanBeDownloaded() throws Exception {
        var old = invoice(30);

        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", old.id()).param("levelId", firstLevelId()))
                .andExpect(redirectedUrl("/dunning"))
                .andExpect(flash().attribute("message", "dunning.created.ok"));

        var id = jdbc.sql("SELECT id FROM dunning").query(String.class).single();

        mvc.perform(get("/dunning/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/dunning"))
                .andExpect(content().string(containsString("MA" + TODAY.getYear() + "00001")));
        mvc.perform(get("/dunning/" + "unknown" + "/pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailPageShowsTheLetterWithItsMailPanel() throws Exception {
        var old = invoice(30);

        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", old.id())
                .param("levelId", firstLevelId()));

        var id = jdbc.sql("SELECT id FROM dunning").query(String.class).single();

        mvc.perform(get("/dunning/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MA" + TODAY.getYear() + "00001")))
                .andExpect(content().string(containsString("/mail/panel?type=DUNNING&amp;id=" + id)));
        mvc.perform(get("/dunning/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lettersShowTheCurrentStateOfTheirInvoiceAndSettledOnesComeLastAndCanBeFilteredOut() throws Exception {
        var paid = invoice(40);
        var open = invoice(30);

        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", paid.id())
                .param("levelId", firstLevelId()));
        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", open.id())
                .param("levelId", firstLevelId()));
        payments.record(paid.id(), new PaymentInput(TODAY, 100_000, PaymentMethod.BANK_TRANSFER, null, null));

        var all = mvc.perform(get("/dunning"))
                .andReturn().getResponse().getContentAsString();
        var onlyOpen = mvc.perform(get("/dunning").param("show", "open"))
                .andReturn().getResponse().getContentAsString();

        assertThat(all).contains("class=\"settled\"").contains("noch 1.000,00");
        assertThat(all.indexOf(open.number())).isLessThan(all.lastIndexOf(paid.number()));
        assertThat(onlyOpen).contains("noch 1.000,00").doesNotContain("settled");

        mvc.perform(get("/dunning/by-invoice/" + paid.id()))
                .andExpect(content().string(containsString("Bezahlt")));
    }

    @Test
    void creatingWithoutConfirmationIsRefused() throws Exception {
        var old = invoice(30);

        mvc.perform(post("/dunning").param("invoiceId", old.id()).param("levelId", firstLevelId()))
                .andExpect(flash().attribute("error", "dunning.error.notConfirmed"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dunning").query(Long.class).single()).isZero();
    }

    @Test
    void confirmDialogShowsInvoiceAmountsAndPreviewLink() throws Exception {
        var old = invoice(30);

        mvc.perform(get("/dunning/confirm").param("invoiceId", old.id()).param("levelId", firstLevelId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(old.number())))
                .andExpect(content().string(containsString("Müller GmbH")))
                .andExpect(content().string(containsString("1.000,00")))
                .andExpect(content().string(containsString("/dunning/preview.pdf")))
                .andExpect(content().string(containsString("dunning-confirm")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dunning").query(Long.class).single()).isZero();
    }

    @Test
    void confirmDialogForAnInvoiceThatIsNotDueShowsTheError() throws Exception {
        var young = invoice(0);

        mvc.perform(get("/dunning/confirm").param("invoiceId", young.id()).param("levelId", firstLevelId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("keine Mahnung möglich")))
                .andExpect(content().string(not(containsString("dunning-submit"))));
    }

    @Test
    void creatingTwiceShowsTheErrorAndKeepsOneLetter() throws Exception {
        var old = invoice(30);

        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", old.id()).param("levelId", firstLevelId())
                .param("from", "invoice"));
        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", old.id()).param("levelId", firstLevelId())
                        .param("from", "invoice"))
                .andExpect(redirectedUrl("/invoices/" + old.id()))
                .andExpect(flash().attribute("error", "dunning.error.issued"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dunning").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invoicePagePanelOffersLevelsAndShowsLetters() throws Exception {
        var old = invoice(30);

        mvc.perform(get("/dunning/by-invoice/" + old.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mahnung erstellen")));

        mvc.perform(post("/dunning").param("confirmed", "on").param("invoiceId", old.id()).param("levelId", firstLevelId()));

        mvc.perform(get("/dunning/by-invoice/" + old.id()))
                .andExpect(content().string(containsString("MA" + TODAY.getYear() + "00001")));
    }

    @Test
    void panelIsEmptyForAnInvoiceThatIsNotOverdue() throws Exception {
        var young = invoice(0);

        mvc.perform(get("/dunning/by-invoice/" + young.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Mahnung erstellen"))));
    }

    @Test
    void invoiceListMarksSuggestionsAndFiltersThem() throws Exception {
        var old = invoice(30);
        var young = invoice(0);

        var all = mvc.perform(get("/invoices"))
                .andReturn().getResponse().getContentAsString();
        var suggested = mvc.perform(get("/invoices").param("status", "suggested"))
                .andReturn().getResponse().getContentAsString();

        assertThat(all).contains("Mahnung vorgeschlagen").contains(old.number()).contains(young.number());
        assertThat(suggested).contains(old.number()).doesNotContain(young.number());
    }

    @Test
    void levelPageShowsTheLevelsAndSavesChanges() throws Exception {
        var level = levels.list().getFirst();

        mvc.perform(get("/dunning/levels"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahlungserinnerung")))
                .andExpect(content().string(containsString("Mahnstufen")))
                .andExpect(content().string(containsString("Neue Stufe")))
                .andExpect(content().string(containsString("/dunning/levels/" + level.id())));
        mvc.perform(get("/dunning/levels/" + level.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mahnstufe bearbeiten")))
                .andExpect(content().string(containsString("Auslöser und Frist")))
                .andExpect(content().string(containsString("data-format-target=\"header\"")))
                .andExpect(content().string(containsString("Informationen zu Formatierung und Platzhaltern")))
                .andExpect(content().string(containsString("{mahngebuehr}")))
                .andExpect(content().string(not(containsString("<details class=\"collapsible format-info\" open"))))
                .andExpect(content().string(containsString("value=\"Zahlungserinnerung\"")));
        mvc.perform(get("/dunning/levels/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Neue Stufe")));
        mvc.perform(get("/dunning/levels/unknown")).andExpect(status().isNotFound());

        mvc.perform(post("/dunning/levels/" + level.id()).param("level", "1").param("name", "Erinnerung")
                        .param("daysAfterDue", "10").param("fee", "5,00").param("interest", "9").param("newDueDays", "10")
                        .param("headerText", "{anrede}").param("footerText", "Bitte zahlen bis {neue_frist}")
                        .param("active", "true"))
                .andExpect(redirectedUrl("/dunning/levels"))
                .andExpect(flash().attribute("message", "dunning.level.saved"));

        var saved = levels.find(level.id()).orElseThrow();
        assertThat(saved.name()).isEqualTo("Erinnerung");
        assertThat(saved.feeCents()).isEqualTo(500);
        assertThat(saved.interestBp()).isEqualTo(900);
        assertThat(saved.daysAfterDue()).isEqualTo(10);
    }

    @Test
    void invalidLevelIsRejectedWithAMessage() throws Exception {
        var level = levels.list().getFirst();

        mvc.perform(post("/dunning/levels/" + level.id()).param("level", "2").param("name", "Doppelt")
                        .param("daysAfterDue", "10").param("fee", "0").param("interest", "0").param("newDueDays", "7")
                        .param("headerText", "Kopf").param("footerText", "Fuß"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", "dunning.level.error.taken"))
                .andExpect(content().string(containsString("Doppelt")));
        mvc.perform(post("/dunning/levels/" + level.id()).param("level", "1").param("name", "Unbekannt")
                        .param("daysAfterDue", "10").param("fee", "0").param("interest", "0").param("newDueDays", "7")
                        .param("headerText", "Hallo {gibtsnicht}").param("footerText", "Fuß"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
        mvc.perform(post("/dunning/levels/" + level.id()).param("level", "1").param("name", "Zahl")
                        .param("daysAfterDue", "zehn").param("fee", "0").param("interest", "0").param("newDueDays", "7")
                        .param("headerText", "Kopf").param("footerText", "Fuß"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", "dunning.level.error.number"))
                .andExpect(content().string(containsString("zehn")));

        assertThat(levels.find(level.id()).orElseThrow().name()).isEqualTo("Zahlungserinnerung");
    }

    @Test
    void newLevelCanBeAdded() throws Exception {
        mvc.perform(post("/dunning/levels").param("level", "4").param("name", "Inkasso").param("daysAfterDue", "60")
                        .param("fee", "0").param("interest", "0").param("newDueDays", "5").param("headerText", "Kopf")
                        .param("footerText", "Fuß").param("active", "true"))
                .andExpect(flash().attribute("message", "dunning.level.created"));

        assertThat(levels.list()).extracting(DunningLevelInfo::name).contains("Inkasso");
    }
}
