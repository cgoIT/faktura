package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.customer.ContactInput;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SqliteTest
class InvoicePagesTest {

    private static final String HOUR = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CatalogService catalog;

    private String customerId;

    @AfterEach
    void cleanUp() {
        TestArchive.clear();
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
    }

    @BeforeEach
    void setUp() {
        cleanUp();
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

    private static RequestPostProcessor setIssueDate(String date) {
        return request -> {
            request.setParameter("issueDate", date);

            return request;
        };
    }

    private MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder request) {
        return request.header("HX-Request", "true");
    }

    private MockHttpServletRequestBuilder exampleForm(MockHttpServletRequestBuilder request) {
        return request.param("customerId", customerId).param("issueDate", "2026-10-20").param("paymentDays", "14")
                .param("subject", "Webentwicklung Oktober 2026").param("discountType", "PERCENT")
                .param("discountValue", "5")
                .param("items[0].description", "Webentwicklung").param("items[0].quantity", "6")
                .param("items[0].unitCode", "Std").param("items[0].price", "85,00").param("items[0].discount", "10")
                .param("items[0].taxCategory", "E").param("items[0].taxRate", "0")
                .param("items[1].description", "Domain-Registrierung").param("items[1].quantity", "1")
                .param("items[1].unitCode", "Stk").param("items[1].price", "12,00")
                .param("items[1].discount", "").param("items[1].taxCategory", "E").param("items[1].taxRate", "0")
                .param("items[2].description", "Anfahrt").param("items[2].quantity", "1")
                .param("items[2].unitCode", "Psch").param("items[2].price", "40,00")
                .param("items[2].discount", "").param("items[2].taxCategory", "E").param("items[2].taxRate", "0");
    }

    @Test
    void pagesRenderInGermanWithoutExternalResources() throws Exception {
        for (var path : new String[] {"/invoices", "/invoices/new"}) {
            var html = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(html).contains("/css/app.css").contains("/js/htmx.min.js").contains("Rechnungen");
            assertThat(html).doesNotContain("https://").doesNotContain("http://");
        }
    }

    @Test
    void draftCanBeCreatedShownInTheListEditedAndDeleted() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andExpect(redirectedUrlPattern("/invoices/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("485,45")))
                .andExpect(content().string(containsString("Webentwicklung")))
                .andExpect(content().string(containsString("tax-category-select")))
                .andExpect(content().string(containsString("E – Steuerbefreit")))
                .andExpect(content().string(containsString("value=\"2026-10-20\"")));
        mvc.perform(get("/invoices"))
                .andExpect(content().string(containsString("Müller GmbH")))
                .andExpect(content().string(containsString("Entwurf")))
                .andExpect(content().string(containsString("Webentwicklung Oktober 2026")))
                .andExpect(content().string(containsString("485,45")));
        mvc.perform(htmx(get("/invoices")).param("q", "oktober"))
                .andExpect(content().string(containsString("Müller GmbH")));
        mvc.perform(htmx(get("/invoices")).param("q", "gibtesnicht"))
                .andExpect(content().string(not(containsString("Müller GmbH"))));
        mvc.perform(htmx(get("/invoices")).param("q", "MÜLLER"))
                .andExpect(content().string(containsString("Müller GmbH")))
                .andExpect(content().string(not(containsString("<html"))));

        mvc.perform(post(location).param("version", "1").param("customerId", customerId).param("issueDate", "2026-10-21")
                        .param("items[0].description", "Nur eins").param("items[0].quantity", "2")
                        .param("items[0].price", "50").param("items[0].taxCategory", "E").param("items[0].taxRate", "0"))
                .andExpect(redirectedUrl(location));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_item").query(Long.class).single()).isEqualTo(1);

        mvc.perform(post(location + "/delete"))
                .andExpect(redirectedUrl("/invoices"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document").query(Long.class).single()).isZero();
    }

    @Test
    void savingWithoutOrWithAnOldVersionIsRefusedAndKeepsTheDraft() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(exampleForm(post(location)).param("subject", "Ohne Version"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("inzwischen von jemand anderem gespeichert")));
        mvc.perform(exampleForm(post(location)).param("version", "1"))
                .andExpect(redirectedUrl(location));
        mvc.perform(exampleForm(post(location)).param("version", "1").param("subject", "Veraltet"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("inzwischen von jemand anderem gespeichert")));
        assertThat(jdbc.sql("SELECT subject FROM document").query(String.class).single())
                .isEqualTo("Webentwicklung Oktober 2026");
    }

    @Test
    void draftIsFinalizedShownAsPdfAndCancelled() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(exampleForm(post(location + "/preview.pdf")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get(location))
                .andExpect(content().string(containsString("/preview.pdf")))
                .andExpect(content().string(containsString("form=\"invoice-form\"")))
                .andExpect(content().string(containsString("Finalisieren")));

        jdbc.sql("UPDATE document SET subject = 'Alter Stand'").update();

        mvc.perform(exampleForm(post(location + "/finalize")).param("version", "1").param("reviewed", "on"))
                .andExpect(redirectedUrl(location));
        assertThat(jdbc.sql("SELECT subject FROM document").query(String.class).single())
                .isEqualTo("Webentwicklung Oktober 2026");
        mvc.perform(get(location))
                .andExpect(content().string(containsString("RE2026")))
                .andExpect(content().string(containsString("Rechnung stornieren")))
                .andExpect(content().string(not(containsString("Entwurf speichern"))));

        var pdf = mvc.perform(get(location + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        mvc.perform(post(location))
                .andExpect(redirectedUrl(location));
        assertThat(jdbc.sql("SELECT subject FROM document").query(String.class).single())
                .isEqualTo("Webentwicklung Oktober 2026");

        var cancellation = mvc.perform(post(location + "/cancel"))
                .andExpect(redirectedUrlPattern("/invoices/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        mvc.perform(get(cancellation))
                .andExpect(content().string(containsString("ST2026")));
        mvc.perform(get(location))
                .andExpect(content().string(containsString("Storniert durch Beleg")))
                .andExpect(content().string(not(containsString("Rechnung stornieren"))));
        mvc.perform(get("/invoices"))
                .andExpect(content().string(containsString("Müller GmbH")));
        mvc.perform(exampleForm(post(location + "/preview.pdf")))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewDialogShowsTheDocumentAsItWouldBeFinalizedAndStoresNothing() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        jdbc.sql("UPDATE document SET subject = 'Alter Stand'").update();

        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))).with(request -> {
            request.setParameter("paymentDays", "30");
            request.setParameter("issueDate", "2099-10-20");

            return request;
        }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rechnung prüfen")))
                .andExpect(content().string(containsString("Müller GmbH")))
                .andExpect(content().string(containsString("Webentwicklung")))
                .andExpect(content().string(containsString("485,45")))
                .andExpect(content().string(containsString("Wird beim Finalisieren vergeben")))
                .andExpect(content().string(containsString("19.11.2099")))
                .andExpect(content().string(containsString("name=\"reviewed\"")))
                .andExpect(content().string(containsString("id=\"rv-period-hint\"")))
                .andExpect(content().string(containsString("disabled")));

        assertThat(jdbc.sql("SELECT subject FROM document").query(String.class).single()).isEqualTo("Alter Stand");
        assertThat(jdbc.sql("SELECT number FROM document").query(String.class).optional()).isEmpty();
    }

    @Test
    void reviewDialogPresetsTodayWhenTheSavedInvoiceDateIsInThePast() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var today = LocalDate.now();

        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"" + today + "\"")))
                .andExpect(content().string(containsString("Vorbelegt ist heute")))
                .andExpect(content().string(containsString(today.plusDays(14)
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))));
    }

    @Test
    void reviewDialogKeepsAFutureInvoiceDateAndTheSummaryReloadKeepsAnyDate() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))).with(setIssueDate("2099-01-05")))
                .andExpect(content().string(containsString("value=\"2099-01-05\"")))
                .andExpect(content().string(not(containsString("Vorbelegt ist heute"))));
        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))).header("HX-Target", "review-summary")
                        .with(setIssueDate("2026-01-05")))
                .andExpect(content().string(containsString("19.01.2026")))
                .andExpect(content().string(not(containsString("Vorbelegt ist heute"))));
    }

    @Test
    void reviewSummaryAloneIsReturnedWhenTheSummaryIsTheTarget() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))).header("HX-Target", "review-summary"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"review-summary\"")))
                .andExpect(content().string(not(containsString("rv-reviewed"))));
    }

    @Test
    void reviewShowsTheErrorAndBlocksFinalizingForInvalidContent() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(htmx(exampleForm(post(location + "/finalize-review"))).param("serviceFrom", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-blocked=\"true\"")));
    }

    @Test
    void finalizingWithoutConfirmationIsRefusedAndNothingChanges() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(exampleForm(post(location + "/finalize")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prüfdialog")));

        assertThat(jdbc.sql("SELECT status FROM document").query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT number FROM document").query(String.class).optional()).isEmpty();
    }

    @Test
    void finalizingWithInvalidFormSavesAndFinalizesNothing() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(exampleForm(post(location + "/finalize")).param("reviewed", "on").param("items[0].price", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("abc")));

        assertThat(jdbc.sql("SELECT status FROM document").query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT number FROM document").query(String.class).optional()).isEmpty();
    }

    @Test
    void changingTheDateRedrawsTheItemsWithTheCategoriesOfThatDaysProfile() throws Exception {
        jdbc.sql("UPDATE tax_profile SET valid_to = '2026-12-31', allowed_categories = 'E'").update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category)
                VALUES ('p2', 'c1', '2027-01-01', 'REGULAR', 1900, 'S')
                """).update();

        mvc.perform(htmx(exampleForm(post("/invoices/items/refresh"))))
                .andExpect(content().string(not(containsString("value=\"AE\""))));
        mvc.perform(htmx(exampleForm(post("/invoices/items/refresh"))).param("serviceFrom", "2027-02-01"))
                .andExpect(content().string(containsString("value=\"AE\"")));
    }

    @Test
    void newInvoiceStartsWithTheDefaultTextsAndOffersTextTemplatesAndContacts() throws Exception {
        customers.addContact(customerId, new ContactInput("Erika", "Beispiel"));

        mvc.perform(get("/invoices/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("in Rechnung zu stellen")))
                .andExpect(content().string(containsString("Ich bedanke mich für die gute Zusammenarbeit")))
                .andExpect(content().string(containsString("data-fill-target=\"headerText\"")))
                .andExpect(content().string(containsString("<details class=\"card collapsible\"")))
                .andExpect(content().string(containsString("name=\"contactId\"")));
        mvc.perform(get("/invoices/contact-field").param("customerId", customerId))
                .andExpect(content().string(containsString("Erika Beispiel")));
        mvc.perform(get("/invoices/contact-field"))
                .andExpect(content().string(not(containsString("Erika Beispiel"))));
    }

    @Test
    void invalidInputKeepsTheEnteredValuesAndShowsTheError() throws Exception {
        mvc.perform(exampleForm(post("/invoices")).param("items[0].price", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte einen Betrag")))
                .andExpect(content().string(containsString("Webentwicklung")));
        mvc.perform(exampleForm(post("/invoices")).with(request -> {
                    request.setParameter("issueDate", "kein-datum");

                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte die markierten Eingaben")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document").query(Long.class).single()).isZero();
    }

    /**
     * Replaces form values of a request; plain {@code param} would add a second value to the example form.
     */
    private static MockHttpServletRequestBuilder override(MockHttpServletRequestBuilder request, String... pairs) {
        return request.with(servletRequest -> {
            for (var i = 0; i < pairs.length; i += 2) {
                servletRequest.setParameter(pairs[i], pairs[i + 1]);
            }

            return servletRequest;
        });
    }

    private static String itemRow(String html, int index) {
        var start = html.indexOf("name=\"items[" + index + "].description\"");
        var end = html.indexOf("name=\"items[" + (index + 1) + "].description\"", start);

        return html.substring(start, end < 0 ? html.indexOf("</table>", start) : end);
    }

    @Test
    void errorOfAnItemShowsAtTheFieldOfItsRow() throws Exception {
        var html = mvc.perform(override(exampleForm(post("/invoices")), "items[1].price", "12,0x"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(itemRow(html, 0)).doesNotContain("field-error");
        assertThat(itemRow(html, 1)).contains("field-error").contains("Bitte einen Betrag");
        assertThat(itemRow(html, 2)).doesNotContain("field-error");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document").query(Long.class).single()).isZero();
    }

    @Test
    void itemCopyIsAppendedWithAllValues() throws Exception {
        var html = mvc.perform(htmx(post("/invoices/items/copy")).param("index", "0")
                        .param("customerId", customerId).param("issueDate", "2026-10-20")
                        .param("items[0].description", "Webentwicklung").param("items[0].quantity", "6")
                        .param("items[0].unitCode", "Std").param("items[0].price", "85,00")
                        .param("items[0].discount", "10").param("items[0].taxCategory", "E")
                        .param("items[0].taxRate", "0")
                        .param("items[1].description", "Domain").param("items[1].quantity", "1")
                        .param("items[1].price", "12,00").param("items[1].taxCategory", "E")
                        .param("items[1].taxRate", "0"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("name=\"items[2].description\"");
        assertThat(itemRow(html, 2)).contains("Webentwicklung").contains("85,00").contains("value=\"10\"");
    }

    @Test
    void serviceRuleOfAnItemShowsAtItsRowAndStaysAtItAfterARowIsRemoved() throws Exception {
        var html = mvc.perform(override(exampleForm(post("/invoices")), "items[1].description", " "))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(itemRow(html, 0)).doesNotContain("field-error");
        assertThat(itemRow(html, 1)).contains("field-error");
        assertThat(html).contains("Bitte die markierten Eingaben");

        var removed = mvc.perform(htmx(post("/invoices/items/remove")).param("index", "0")
                        .param("customerId", customerId).param("issueDate", "2026-10-20")
                        .param("items[0].description", "Webentwicklung").param("items[0].quantity", "6")
                        .param("items[0].unitCode", "Std").param("items[0].price", "85,00")
                        .param("items[0].taxCategory", "E").param("items[0].taxRate", "0")
                        .param("items[1].description", " ").param("items[1].quantity", "1")
                        .param("items[1].price", "12,00").param("items[1].taxCategory", "E")
                        .param("items[1].taxRate", "0"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(removed).contains("name=\"items[0].description\"").doesNotContain("name=\"items[1].description\"");

        var again = mvc.perform(post("/invoices").param("customerId", customerId).param("issueDate", "2026-10-20")
                        .param("items[0].description", " ").param("items[0].quantity", "1")
                        .param("items[0].price", "12,00").param("items[0].taxCategory", "E")
                        .param("items[0].taxRate", "0"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(itemRow(again, 0)).contains("field-error");
    }

    @Test
    void errorsOfSeveralFieldsShowAtOnce() throws Exception {
        var html = mvc.perform(override(exampleForm(post("/invoices")), "paymentDays", "x", "items[0].quantity", "0",
                        "items[2].discount", "101"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(itemRow(html, 0)).contains("field-error");
        assertThat(itemRow(html, 1)).doesNotContain("field-error");
        assertThat(itemRow(html, 2)).contains("field-error");
        assertThat(html.substring(html.indexOf("id=\"paymentDays\""), html.indexOf("serviceFrom"))).contains("field-error");
    }

    @Test
    void reviewDialogListsTheViolations() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(override(exampleForm(post(location + "/finalize-review")), "items[0].description", " ",
                        "items[1].quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("notice error")))
                .andExpect(content().string(containsString("Bitte eine Menge")));
    }

    @Test
    void liveCalculationReturnsTotalsAndLineTotalsOutOfBand() throws Exception {
        mvc.perform(htmx(exampleForm(post("/invoices/calculate"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("485,45")))
                .andExpect(content().string(containsString("511,00")))
                .andExpect(content().string(containsString("459,00")))
                .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
                .andExpect(content().string(containsString("id=\"line-total-2\"")))
                .andExpect(content().string(containsString("id=\"totals-top\"")))
                .andExpect(content().string(containsString("id=\"discount-amount\"")))
                .andExpect(content().string(containsString("−25,55 €")))
                .andExpect(content().string(not(containsString("id=\"discountValue\""))))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void liveCalculationAddsTheShippingAfterTheDiscount() throws Exception {
        mvc.perform(htmx(exampleForm(post("/invoices/calculate")).param("shippingName", "Päckchen")
                        .param("shippingAmount", "4,95")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("490,40")))
                .andExpect(content().string(containsString("Versandkosten")))
                .andExpect(content().string(containsString("−25,55 €")));
    }

    @Test
    void draftKeepsCustomerReferenceAndShippingAndTheEditorShowsThem() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")).param("customerReference", "PO-4711")
                        .param("shippingName", "Päckchen").param("shippingAmount", "4,95")
                        .param("shippingTaxCategory", "").param("shippingTaxRate", ""))
                .andExpect(redirectedUrlPattern("/invoices/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"PO-4711\"")))
                .andExpect(content().string(containsString("value=\"Päckchen\"")))
                .andExpect(content().string(containsString("value=\"4,95\"")))
                .andExpect(content().string(containsString("490,40")));
        assertThat(jdbc.sql("SELECT shipping_cents FROM document").query(Long.class).single()).isEqualTo(495);
    }

    @Test
    void shippingBlockIsClosedWithoutShippingAndOpenWithIt() throws Exception {
        var plain = mvc.perform(get("/invoices/new"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(plain).contains("data-remember=\"shipping\"").doesNotContain("data-forced");
        assertThat(plain).containsPattern("<details class=\"collapsible shipping-section\"(?![^>]*open)");

        var location = mvc.perform(exampleForm(post("/invoices")).param("shippingName", "Päckchen")
                        .param("shippingAmount", "4,95"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var withShipping = mvc.perform(get(location))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withShipping).contains("data-forced=\"true\"").containsPattern("shipping-section\"[^>]*open=\"open\"");
    }

    @Test
    void shippingErrorsAreShownAtTheShippingFields() throws Exception {
        mvc.perform(exampleForm(post("/invoices")).param("shippingAmount", "4,95"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bezeichnung für die Versandkosten")));
        mvc.perform(exampleForm(post("/invoices")).param("shippingName", "Paket").param("shippingAmount", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("shippingAmount")));
    }

    @Test
    void liveCalculationWithInvalidNumberShowsAMessageInsteadOfFailing() throws Exception {
        mvc.perform(htmx(exampleForm(post("/invoices/calculate"))))
                .andExpect(content().string(containsString("hx-swap-oob")));
        mvc.perform(htmx(exampleForm(post("/invoices/items/refresh"))))
                .andExpect(content().string(containsString("id=\"catalog-results\"")))
                .andExpect(content().string(containsString("hx-swap-oob")));
        mvc.perform(htmx(exampleForm(post("/invoices/calculate"))).param("items[1].quantity", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte eine Menge")));
    }

    @Test
    void catalogDialogListsAllEntriesAndSearchNarrowsThemDown() throws Exception {
        for (var number = 1; number <= 12; number++) {
            catalog.create(new CatalogItemInput("Beratung %02d".formatted(number), "Tagessatz", HOUR, 9000, null));
        }

        catalog.create(new CatalogItemInput("Hosting", "Webhosting je Monat", HOUR, 1500, null));

        mvc.perform(get("/invoices/new"))
                .andExpect(content().string(containsString("<dialog id=\"catalog-dialog\"")))
                .andExpect(content().string(containsString("data-open-dialog=\"catalog-dialog\"")))
                .andExpect(content().string(containsString("data-catalog-search")))
                .andExpect(content().string(containsString("/invoices/catalog/search")))
                .andExpect(content().string(containsString("13 Leistungen")))
                .andExpect(content().string(containsString("Beratung 12")))
                .andExpect(content().string(containsString("Hosting")))
                .andExpect(content().string(containsString("Tagessatz")));
        mvc.perform(htmx(exampleForm(post("/invoices/catalog/search"))).param("catalogQuery", "beratung"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("12 Leistungen")))
                .andExpect(content().string(containsString("Beratung 12")))
                .andExpect(content().string(not(containsString("Hosting"))))
                .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(htmx(exampleForm(post("/invoices/catalog/search"))).param("catalogQuery", "WEBHOSTING"))
                .andExpect(content().string(containsString("Hosting")))
                .andExpect(content().string(not(containsString("Beratung"))));
        mvc.perform(htmx(exampleForm(post("/invoices/catalog/search"))).param("catalogQuery", "gibtesnicht"))
                .andExpect(content().string(containsString("Keine Leistung gefunden")));
    }

    @Test
    void itemsCanBeAddedFromCatalogAndFreeTextAndRemoved() throws Exception {
        var entry = catalog.create(new CatalogItemInput("Hosting", "Monatlich", HOUR, 2500, null));

        mvc.perform(htmx(exampleForm(post("/invoices/items/add-catalog"))).param("catalogItemId", entry.id()))
                .andExpect(content().string(containsString("items[3].description")))
                .andExpect(content().string(containsString("Hosting")))
                .andExpect(content().string(containsString("25,00")))
                .andExpect(content().string(not(containsString("Aus dem Katalog kopiert"))));
        mvc.perform(htmx(exampleForm(post("/invoices/items/add"))))
                .andExpect(content().string(containsString("items[3].description")))
                .andExpect(content().string(containsString("data-full=\"E – Steuerbefreit\"")));
        mvc.perform(htmx(exampleForm(post("/invoices/items/remove"))).param("index", "0"))
                .andExpect(content().string(not(containsString("items[2].description"))))
                .andExpect(content().string(not(containsString("Webentwicklung"))))
                .andExpect(content().string(containsString("Domain-Registrierung")));
    }

    @Test
    void finalizedInvoiceIsShownReadOnlyAndCannotBeChanged() throws Exception {
        var location = mvc.perform(exampleForm(post("/invoices")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        jdbc.sql("UPDATE document SET status = 'FINAL', number = 'RE202600001'").update();

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RE202600001")))
                .andExpect(content().string(containsString("kann nicht mehr geändert werden")))
                .andExpect(content().string(not(containsString("Entwurf speichern"))));
        mvc.perform(post(location + "/delete"))
                .andExpect(redirectedUrl(location));
        mvc.perform(exampleForm(post(location)))
                .andExpect(redirectedUrl(location));
        mvc.perform(get(location))
                .andExpect(content().string(not(containsString("Entwurf speichern"))));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM document").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unknownInvoiceIsNotFound() throws Exception {
        mvc.perform(get("/invoices/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
