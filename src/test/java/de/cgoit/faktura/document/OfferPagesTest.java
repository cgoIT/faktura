package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SqliteTest
class OfferPagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private InvoiceService invoices;

    private String customerId;

    @AfterEach
    void cleanUp() {
        TestArchive.clear();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM offer_event").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, tax_number, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '12/345/67890', '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E')
                """).update();
        customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null)).id();
    }

    private MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder request) {
        return request.param("customerId", customerId)
                .param("issueDate", LocalDate.now().toString())
                .param("validUntil", LocalDate.now().plusDays(20).toString())
                .param("subject", "Relaunch")
                .param("discountType", "PERCENT")
                .param("items[0].description", "Webentwicklung").param("items[0].quantity", "6")
                .param("items[0].unitCode", "Std").param("items[0].price", "85,00").param("items[0].discount", "")
                .param("items[0].taxCategory", "E").param("items[0].taxRate", "0");
    }

    @Test
    void itemCopyWorksForOffers() throws Exception {
        mvc.perform(form(post("/offers/items/copy")).param("index", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"items[1].description\"")));
    }

    private String createOffer() throws Exception {
        var location = mvc.perform(form(post("/offers")))
                .andExpect(redirectedUrlPattern("/offers/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        return location.substring("/offers/".length());
    }

    @Test
    void theNavigationLinksToTheOffers() throws Exception {
        mvc.perform(get("/offers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/offers\"")))
                .andExpect(content().string(containsString("Neues Angebot")))
                .andExpect(content().string(not(containsString("Neue Rechnung"))));
    }

    @Test
    void theOfferEditorAsksForTheValidityInsteadOfThePaymentTerm() throws Exception {
        mvc.perform(get("/offers/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"validUntil\"")))
                .andExpect(content().string(not(containsString("name=\"paymentDays\""))))
                .andExpect(content().string(containsString("action=\"/offers\"")))
                .andExpect(content().string(containsString("/offers/calculate")));
        mvc.perform(get("/invoices/new"))
                .andExpect(content().string(containsString("name=\"paymentDays\"")))
                .andExpect(content().string(not(containsString("name=\"validUntil\""))));
    }

    @Test
    void anOfferIsCreatedShownEditedAndDeletedUnderOffers() throws Exception {
        var id = createOffer();

        mvc.perform(get("/offers/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Webentwicklung")))
                .andExpect(content().string(containsString("value=\"" + LocalDate.now().plusDays(20) + "\"")));
        mvc.perform(get("/offers"))
                .andExpect(content().string(containsString("Müller GmbH")))
                .andExpect(content().string(containsString("Entwurf")));
        mvc.perform(get("/invoices"))
                .andExpect(content().string(not(containsString("Müller GmbH"))));

        var version = invoices.find(id).orElseThrow().version();

        mvc.perform(form(post("/offers/" + id)).param("version", String.valueOf(version)).param("subject", "Neu"))
                .andExpect(redirectedUrlPattern("/offers/*"));
        mvc.perform(post("/offers/" + id + "/delete"))
                .andExpect(redirectedUrl("/offers"));

        assertThat(invoices.find(id)).isEmpty();
    }

    @Test
    void anOfferIsNotReachableAsInvoiceAndAnInvoiceNotAsOffer() throws Exception {
        var offerId = createOffer();
        var invoiceId = invoices.createDraft(new InvoiceInput(customerId, LocalDate.now(), null, null, 14, "x", null,
                null, null, List.of(new InvoiceItemInput("A", 1000, "Std", 100, 0, "E", 0, null)))).id();

        mvc.perform(get("/invoices/" + offerId)).andExpect(status().isNotFound());
        mvc.perform(get("/offers/" + invoiceId)).andExpect(status().isNotFound());
        mvc.perform(post("/offers/" + invoiceId + "/delete")).andExpect(status().isNotFound());
        mvc.perform(get("/invoices/" + offerId + "/pdf")).andExpect(status().isNotFound());
        mvc.perform(get("/offers/" + invoiceId + "/pdf")).andExpect(status().isNotFound());
    }

    @Test
    void finalizingShowsTheReviewAndLocksTheOfferWithoutPaymentsOrCancel() throws Exception {
        var id = createOffer();
        var version = invoices.find(id).orElseThrow().version();

        mvc.perform(form(post("/offers/" + id + "/finalize-review")).param("version", String.valueOf(version)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Angebot prüfen")))
                .andExpect(content().string(containsString("rv-validUntil")))
                .andExpect(content().string(containsString("/offers/" + id + "/finalize")));
        mvc.perform(form(post("/offers/" + id + "/finalize")).param("version", String.valueOf(version))
                        .param("reviewed", "on"))
                .andExpect(redirectedUrlPattern("/offers/*"));

        var view = mvc.perform(get("/offers/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(view).contains("AN" + LocalDate.now().getYear() + "00001")
                .contains("Gültig bis")
                .doesNotContain("Zahlungen")
                .doesNotContain("stornieren");
        mvc.perform(post("/offers/" + id + "/cancel")).andExpect(status().isNotFound());
        mvc.perform(post("/offers/" + id + "/payments")).andExpect(status().isNotFound());
    }

    private String finalOfferId() throws Exception {
        var id = createOffer();
        var version = invoices.find(id).orElseThrow().version();

        mvc.perform(form(post("/offers/" + id + "/finalize")).param("version", String.valueOf(version))
                .param("reviewed", "on"));

        return id;
    }

    @Test
    void theFinalOfferShowsItsCourseWithTheActions() throws Exception {
        var id = finalOfferId();

        mvc.perform(get("/offers/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verlauf")))
                .andExpect(content().string(containsString("Rechnung erstellen")))
                .andExpect(content().string(containsString("Als abgelehnt vermerken")))
                .andExpect(content().string(containsString("/offers/" + id + "/invoice")))
                .andExpect(content().string(containsString("/mail/panel")));
    }

    @Test
    void rejectingRecordsTheEventAndHidesTheRejectAction() throws Exception {
        var id = finalOfferId();

        mvc.perform(post("/offers/" + id + "/reject"))
                .andExpect(redirectedUrl("/offers/" + id));
        mvc.perform(get("/offers/" + id))
                .andExpect(content().string(containsString("Abgelehnt")))
                .andExpect(content().string(not(containsString("Als abgelehnt vermerken"))));
        mvc.perform(get("/offers?status=rejected"))
                .andExpect(content().string(containsString("Müller GmbH")));
    }

    @Test
    void creatingAnInvoiceOpensTheDraftAndTheOfferLinksToIt() throws Exception {
        var id = finalOfferId();

        var location = mvc.perform(post("/offers/" + id + "/invoice"))
                .andExpect(redirectedUrlPattern("/invoices/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Webentwicklung")))
                .andExpect(content().string(containsString("name=\"paymentDays\"")));
        mvc.perform(get("/offers/" + id))
                .andExpect(content().string(containsString("Rechnungen dazu")))
                .andExpect(content().string(containsString("Angenommen")))
                .andExpect(content().string(containsString("Zu diesem Angebot gibt es schon eine Rechnung")));
    }

    @Test
    void offerActionsAreNotAvailableForDraftsOrInvoices() throws Exception {
        var draftId = createOffer();
        var invoiceId = invoices.createDraft(new InvoiceInput(customerId, LocalDate.now(), null, null, 14, "x", null,
                null, null, List.of(new InvoiceItemInput("A", 1000, "Std", 100, 0, "E", 0, null)))).id();

        mvc.perform(post("/offers/" + draftId + "/reject")).andExpect(redirectedUrl("/offers/" + draftId));
        mvc.perform(post("/offers/" + draftId + "/invoice")).andExpect(redirectedUrl("/offers/" + draftId));
        mvc.perform(post("/offers/" + invoiceId + "/reject")).andExpect(status().isNotFound());
        mvc.perform(post("/offers/" + invoiceId + "/invoice")).andExpect(status().isNotFound());
    }

    @Test
    void theOfferPdfIsTitledAsOfferWithValidityAndWithoutGirocode() throws Exception {
        var id = createOffer();
        var version = invoices.find(id).orElseThrow().version();

        mvc.perform(form(post("/offers/" + id + "/finalize")).param("version", String.valueOf(version))
                .param("reviewed", "on"));

        var pdf = mvc.perform(get("/offers/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (var document = Loader.loadPDF(pdf)) {
            var text = new PDFTextStripper().getText(document);

            assertThat(text).contains("ANGEBOT").contains("Angebotsnummer:").contains("Gültig bis:")
                    .contains(LocalDate.now().plusDays(20).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                    .doesNotContain("Girocode")
                    .doesNotContain("RECHNUNG");
        }
    }
}
