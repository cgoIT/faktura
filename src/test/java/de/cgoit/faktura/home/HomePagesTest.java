package de.cgoit.faktura.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class HomePagesTest {

    private static final String HOUR = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SetupService setup;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM setup_step").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        TestArchive.clear();
    }

    @Test
    void tilesAreGroupedAndTheOfferTileCountsOpenOffersAndThoseEndingSoon() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14")
                .param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"));

        var customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null)).id();
        var today = LocalDate.now();

        for (var days : new int[] {3, 20}) {
            var draft = invoices.createOfferDraft(new InvoiceInput(customerId, today, null, null, null, "Test", null,
                    null, TotalDiscount.none(), List.of(new InvoiceItemInput("Beratung", 1000, "Std", 10000, 0, "E", 0,
                    null)), null, null, null, today.plusDays(days)));

            finalizer.finalizeInvoice(draft.id());
        }

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tile-group-title")))
                .andExpect(content().string(containsString("Aufgaben")))
                .andExpect(content().string(containsString("1 laufen in 7 Tagen ab")))
                .andExpect(content().string(containsString("href=\"/offers\"")));
    }

    @Test
    void emptyDatabaseShowsTheOpenChecklistAndNoMoney() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Einrichtung")))
                .andExpect(content().string(containsString("Nummernkreise prüfen")))
                .andExpect(content().string(containsString("Noch keine Rechnungen.")))
                .andExpect(content().string(not(containsString("Einrichtung abgeschlossen"))));

        assertThat(setup.items()).noneMatch(SetupItem::done);
    }

    @Test
    void stepsFollowingFromDataTickThemselvesAndManualStepsCanBeTickedAndReopened() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"));
        customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null, null));
        catalog.create(new CatalogItemInput("Webentwicklung", null, HOUR, 8500, null));

        assertThat(done(SetupStep.COMPANY)).isTrue();
        assertThat(done(SetupStep.TAX_PROFILE)).isTrue();
        assertThat(done(SetupStep.CUSTOMERS)).isTrue();
        assertThat(done(SetupStep.CATALOG)).isTrue();
        assertThat(done(SetupStep.INVOICE)).isFalse();
        assertThat(done(SetupStep.NUMBERING)).isFalse();

        mvc.perform(post("/setup/NUMBERING").param("done", "true"))
                .andExpect(redirectedUrl("/"));
        assertThat(done(SetupStep.NUMBERING)).isTrue();

        mvc.perform(post("/setup/NUMBERING"))
                .andExpect(redirectedUrl("/"));
        assertThat(done(SetupStep.NUMBERING)).isFalse();

        mvc.perform(post("/setup/COMPANY").param("done", "false"))
                .andExpect(redirectedUrl("/"));
        assertThat(done(SetupStep.COMPANY)).isTrue();
    }

    @Test
    void completedSetupCollapsesTheChecklist() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"));

        var customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null)).id();

        catalog.create(new CatalogItemInput("Webentwicklung", null, HOUR, 8500, null));
        invoices.createDraft(draft(customerId, LocalDate.now()));
        setup.tick(SetupStep.NUMBERING, true);
        setup.tick(SetupStep.UNITS, true);
        setup.tick(SetupStep.DUNNING_LEVELS, true);

        assertThat(setup.complete()).isTrue();
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("Einrichtung abgeschlossen")))
                .andExpect(content().string(not(containsString("Hake die Schritte ab"))));
    }

    @Test
    void tilesCountDraftsIssuedInvoicesOfTheYearAndCancellations() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"));

        var customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null)).id();
        var today = LocalDate.now();

        invoices.createDraft(draft(customerId, today));
        finalizer.finalizeInvoice(invoices.createDraft(draft(customerId, today)).id());

        var cancelled = finalizer.finalizeInvoice(invoices.createDraft(draft(customerId, today)).id());

        finalizer.cancelInvoice(cancelled.id());

        var html = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("Entwürfe").contains("100,00 €").contains("1 finalisiert, ohne stornierte");
        assertThat(html).containsPattern("tile-value\">1</strong>");
        assertThat(html).contains("Entwurf").contains("RE");
    }

    @Test
    void tilesShowOverdueAmountAndDunningSuggestions() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"));

        var customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null)).id();

        finalizer.finalizeInvoice(invoices.createDraft(draft(customerId, LocalDate.now().minusDays(30))).id());
        finalizer.finalizeInvoice(invoices.createDraft(draft(customerId, LocalDate.now())).id());

        var html = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("Überfällig").contains("1 Rechnungen noch offen").contains("vorgeschlagen");
        assertThat(html).contains("status=overdue").contains("href=\"/dunning\"");
        assertThat(html).containsPattern("tile-value\">1</strong>\\s*<span class=\"tile-note\">vorgeschlagen");
    }

    private boolean done(SetupStep step) {
        return setup.items().stream()
                .filter(item -> item.step() == step)
                .findFirst()
                .orElseThrow()
                .done();
    }

    private static InvoiceInput draft(String customerId, LocalDate date) {
        return new InvoiceInput(customerId, date, null, null, 14, "Test", null, null, TotalDiscount.none(),
                List.of(new InvoiceItemInput("Beratung", 1000, "Std", 10000, 0, "E", 0, null)));
    }
}
