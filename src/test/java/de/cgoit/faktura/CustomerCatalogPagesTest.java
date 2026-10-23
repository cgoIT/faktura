package de.cgoit.faktura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class CustomerCatalogPagesTest {

    private static final String HOUR = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM number_sequence WHERE scope IN ('CUSTOMER', 'CATALOG')").update();
    }

    @Test
    void pagesRenderInGermanWithoutExternalResources() throws Exception {
        for (var path : new String[] {"/customers", "/customers/new", "/catalog", "/catalog/new"}) {
            var html = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(html).contains("/css/app.css").contains("/js/htmx.min.js").contains("Leistungen");
            assertThat(html).doesNotContain("https://").doesNotContain("http://");
        }
    }

    @Test
    void customerCanBeCreatedSearchedArchivedAndRestored() throws Exception {
        var location = mvc.perform(post("/customers").param("name", "Müller GmbH").param("country", "DE")
                        .param("vatId", "DE123456789").param("discount", "5,5"))
                .andExpect(redirectedUrlPattern("/customers/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var id = location.substring(location.lastIndexOf('/') + 1);

        mvc.perform(get(location))
                .andExpect(content().string(containsString("KD0001")))
                .andExpect(content().string(containsString("value=\"5,5\"")));
        assertThat(jdbc.sql("SELECT discount_bp FROM customer WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(550);

        mvc.perform(get("/customers").param("q", "MÜLLER"))
                .andExpect(content().string(containsString("Müller GmbH")));

        mvc.perform(post("/customers/{id}/archive", id)).andExpect(redirectedUrl("/customers"));
        mvc.perform(get("/customers"))
                .andExpect(content().string(not(containsString("Müller GmbH"))));
        mvc.perform(get("/customers").param("status", "archived"))
                .andExpect(content().string(containsString("Müller GmbH")));

        mvc.perform(post("/customers/{id}/restore", id)).andExpect(redirectedUrlPattern("/customers/*"));
        mvc.perform(get("/customers"))
                .andExpect(content().string(containsString("Müller GmbH")));
    }

    @Test
    void customerFormShowsErrorsForInvalidInput() throws Exception {
        mvc.perform(post("/customers").param("name", " ").param("country", "DE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte ausfüllen.")));
        mvc.perform(post("/customers").param("name", "Firma").param("country", "DE").param("vatId", "DE12"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gültige USt-IdNr.")));
        mvc.perform(post("/customers").param("name", "Firma").param("country", "DE").param("discount", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prozentwert")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer").query(Long.class).single()).isZero();
    }

    @Test
    void htmxSearchReturnsOnlyTheRows() throws Exception {
        mvc.perform(post("/customers").param("name", "Müller GmbH").param("country", "DE"));
        mvc.perform(post("/customers").param("name", "Schmidt AG").param("country", "DE"));

        mvc.perform(get("/customers").param("q", "schmidt").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Schmidt AG")))
                .andExpect(content().string(not(containsString("Müller GmbH"))))
                .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(get("/customers").param("q", "schmidt"))
                .andExpect(content().string(containsString("<html")));
        mvc.perform(get("/catalog").header("HX-Request", "true"))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void customerFormShowsAllViolationsAtTheirFields() throws Exception {
        mvc.perform(post("/customers").param("name", " ").param("street", "x".repeat(201)).param("country", "DE")
                        .param("paymentDays", "400"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"name\"[^>]*>\\s*<span class=\"field-error\">Bitte ausfüllen\\.</span>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"street\"[^>]*>\\s*<span class=\"field-error\">Der Wert ist zu lang\\.</span>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"paymentDays\"[^>]*>.*?<span class=\"field-error\">Bitte eine Zahl zwischen 0 und 365.*")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer").query(Long.class).single()).isZero();
    }

    @Test
    void catalogFormShowsTheViolationAtTheRightField() throws Exception {
        mvc.perform(post("/catalog").param("name", "Beratung").param("description", "x".repeat(2001))
                        .param("unitId", HOUR).param("price", "10,00"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*id=\"description\"[^>]*>[^<]*</textarea>\\s*<span class=\"field-error\">Der Wert ist zu lang.*")));
    }

    @Test
    void countryIsASelectionWithGermanNamesAndGermanyAsDefault() throws Exception {
        mvc.perform(get("/customers/new"))
                .andExpect(content().string(containsString("<select id=\"country\" name=\"country\">")))
                .andExpect(content().string(containsString("<option value=\"DE\" selected=\"selected\">Deutschland</option>")))
                .andExpect(content().string(containsString(">Österreich</option>")));
    }

    @Test
    void newCustomersGetZugferdAsDefault() throws Exception {
        mvc.perform(get("/customers/new"))
                .andExpect(content().string(containsString("<option value=\"ZUGFERD\" selected=\"selected\"")));
    }

    @Test
    void storedCountryCodeOutsideTheListStaysSelected() throws Exception {
        var location = mvc.perform(post("/customers").param("name", "Altdaten GmbH").param("country", "DE"))
                .andReturn().getResponse().getRedirectedUrl();
        jdbc.sql("UPDATE customer SET country = 'ZZ' WHERE name = 'Altdaten GmbH'").update();

        mvc.perform(get(location))
                .andExpect(content().string(containsString("<option value=\"ZZ\" selected=\"selected\">ZZ</option>")));
    }

    @Test
    void contactCanBeAddedAndRemoved() throws Exception {
        var location = mvc.perform(post("/customers").param("name", "Müller GmbH").param("country", "DE"))
                .andReturn().getResponse().getRedirectedUrl();

        mvc.perform(post(location + "/contacts").param("firstName", "Erika").param("lastName", "Beispiel").param("email", "erika@example.org"))
                .andExpect(redirectedUrlPattern("/customers/*"));
        mvc.perform(get(location))
                .andExpect(content().string(containsString("Erika Beispiel")));

        var contactId = jdbc.sql("SELECT id FROM customer_contact").query(String.class).single();
        mvc.perform(post(location + "/contacts/" + contactId + "/delete"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_contact").query(Long.class).single()).isZero();
    }

    @Test
    void contactFormAndListShowPhoneAndMobileSeparately() throws Exception {
        var location = mvc.perform(post("/customers").param("name", "Müller GmbH").param("country", "DE"))
                .andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(location + "/contacts/new"))
                .andExpect(content().string(containsString("name=\"mobile\"")))
                .andExpect(content().string(containsString("Mobil")));
        mvc.perform(post(location + "/contacts").param("firstName", "Erika").param("lastName", "Beispiel").param("phone", "030 1234")
                .param("mobile", "0171 5678"));
        mvc.perform(get(location))
                .andExpect(content().string(containsString("030 1234")))
                .andExpect(content().string(containsString("0171 5678")));

        var contactId = jdbc.sql("SELECT id FROM customer_contact").query(String.class).single();
        mvc.perform(get(location + "/contacts/" + contactId))
                .andExpect(content().string(containsString("value=\"0171 5678\"")));
    }

    @Test
    void searchListsReplaceTheWholeTableBodyBecauseTheFragmentIsTheBodyItself() throws Exception {
        for (var path : new String[] {"/customers", "/catalog", "/invoices"}) {
            mvc.perform(get(path))
                    .andExpect(content().string(containsString("hx-swap=\"outerHTML\"")));
            mvc.perform(get(path).header("HX-Request", "true"))
                    .andExpect(content().string(containsString("<tbody id=\"")));
        }
    }

    @Test
    void contactsAreListedReadOnlyAndEditedOnTheirOwnPage() throws Exception {
        var location = mvc.perform(post("/customers").param("name", "Müller GmbH").param("country", "DE"))
                .andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(location + "/contacts/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Neuer Ansprechpartner")));
        mvc.perform(post(location + "/contacts").param("lastName", "").param("salutation", "MR"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Neuer Ansprechpartner")));
        mvc.perform(post(location + "/contacts").param("firstName", "Erika").param("lastName", "Beispiel").param("salutation", "MS")
                        .param("email", "erika@example.org").param("role", "Einkauf"))
                .andExpect(redirectedUrlPattern("/customers/*"));

        var contactId = jdbc.sql("SELECT id FROM customer_contact").query(String.class).single();

        mvc.perform(get(location))
                .andExpect(content().string(containsString("Frau Erika Beispiel")))
                .andExpect(content().string(containsString("Einkauf")))
                .andExpect(content().string(not(containsString("name=\"salutation\""))));
        mvc.perform(get(location + "/contacts/" + contactId))
                .andExpect(content().string(containsString("Ansprechpartner bearbeiten")))
                .andExpect(content().string(containsString("value=\"Erika\"")))
                .andExpect(content().string(containsString("value=\"Beispiel\"")))
                .andExpect(content().string(containsString("Kontaktdaten")))
                .andExpect(content().string(containsString("erika@example.org")));
        mvc.perform(post(location + "/contacts/" + contactId).param("firstName", "Erika").param("lastName", "Muster"))
                .andExpect(redirectedUrlPattern("/customers/*"));
        assertThat(jdbc.sql("SELECT first_name || ' ' || last_name FROM customer_contact").query(String.class).single()).isEqualTo("Erika Muster");
        mvc.perform(get("/customers/unknown/contacts/new")).andExpect(status().isNotFound());
    }

    @Test
    void catalogItemCanBeCreatedChangedAndDeactivated() throws Exception {
        var location = mvc.perform(post("/catalog").param("name", "Webentwicklung").param("unitId", HOUR)
                        .param("price", "1.234,50").param("taxCategory", ""))
                .andExpect(redirectedUrlPattern("/catalog/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        assertThat(jdbc.sql("SELECT unit_price_cents FROM catalog_item").query(Long.class).single())
                .isEqualTo(123450L);
        mvc.perform(get(location))
                .andExpect(content().string(containsString("Steuerbefreit")))
                .andExpect(content().string(containsString("ART0001")))
                .andExpect(content().string(containsString("value=\"1234,50\"")));
        mvc.perform(get("/catalog"))
                .andExpect(content().string(containsString("1.234,50 €")));

        mvc.perform(post(location).param("name", "Webentwicklung").param("unitId", HOUR).param("price", "90"));

        assertThat(jdbc.sql("SELECT unit_price_cents FROM catalog_item").query(Long.class).single()).isEqualTo(9000L);

        mvc.perform(post(location + "/deactivate"));
        mvc.perform(get("/catalog"))
                .andExpect(content().string(not(containsString("Webentwicklung"))));
        mvc.perform(get("/catalog").param("status", "all"))
                .andExpect(content().string(containsString("Inaktiv")));
    }

    @Test
    void catalogFormShowsErrorsForInvalidInput() throws Exception {
        mvc.perform(post("/catalog").param("name", "A").param("unitId", HOUR).param("price", "12,345"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("höchstens zwei Nachkommastellen")));
        mvc.perform(post("/catalog").param("name", "A").param("unitId", HOUR).param("price", "1")
                        .param("taxCategory", "X"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Steuerkategorie aus der Liste")));
        mvc.perform(post("/catalog").param("name", "").param("unitId", "").param("price", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte ausfüllen.")));
        mvc.perform(get("/catalog/unknown")).andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM catalog_item").query(Long.class).single()).isZero();
    }

    @Test
    void unknownIdsAreNotFound() throws Exception {
        mvc.perform(post("/customers/unknown/archive")).andExpect(status().isNotFound());
        mvc.perform(post("/customers/unknown/contacts").param("name", "A")).andExpect(status().isNotFound());
        mvc.perform(post("/catalog/unknown/deactivate")).andExpect(status().isNotFound());
    }

    @Test
    void nonNumericPaymentDaysShowsGermanFieldError() throws Exception {
        mvc.perform(post("/customers").param("name", "Firma").param("country", "DE").param("paymentDays", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahl zwischen 0 und 365")))
                .andExpect(content().string(containsString("value=\"Firma\"")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer").query(Long.class).single()).isZero();
    }
}
