package de.cgoit.faktura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class MasterDataPagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() throws Exception {
        jdbc.sql("DELETE FROM tax_category_reason").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM unit WHERE code NOT IN ('Std', 'Stk', 'Mon', 'Psch', 'Tag')").update();
        jdbc.sql("UPDATE number_pattern SET pattern = 'RE{jahr}{#####}' WHERE scope = 'INVOICE'").update();
        jdbc.sql("DELETE FROM number_sequence").update();
    }

    @Test
    void pagesRenderInGermanWithoutExternalResources() throws Exception {
        for (var path : new String[] {"/", "/company", "/company/tax-profiles", "/units", "/numbering"}) {
            var html = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(html).contains("Unternehmen").contains("/css/app.css").contains("/js/htmx.min.js");
            assertThat(html).doesNotContain("https://").doesNotContain("http://");
        }
    }

    @Test
    void companyFormRejectsUnknownCountryAtTheCountryField() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "XX").param("paymentDays", "14").param("offerValidDays", "30"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte ein Land aus der Liste wählen.")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM company").query(Long.class).single()).isZero();
    }

    @Test
    void companyFormShowsErrorsForInvalidInput() throws Exception {
        mvc.perform(post("/company").param("name", " ").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte ausfüllen.")));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM company").query(Long.class).single()).isZero();
    }

    @Test
    void companyFormSavesAndShowsStoredValues() throws Exception {
        mvc.perform(post("/company")
                        .param("name", "Beispiel Webservice")
                        .param("country", "DE")
                        .param("paymentDays", "30")
                        .param("offerValidDays", "45")
                        .param("iban", "DE02 1203 0000 0000 2020 51"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/company"))
                .andExpect(flash().attribute("message", "company.saved"));

        mvc.perform(get("/company"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Beispiel Webservice")))
                .andExpect(content().string(containsString("DE02 1203 0000 0000 2020 51")))
                .andExpect(content().string(containsString("Gültigkeit von Angeboten")))
                .andExpect(content().string(containsString("value=\"45\"")));
    }

    @Test
    void taxProfilePageAsksForCompanyFirst() throws Exception {
        mvc.perform(get("/company/tax-profiles"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stammdaten des Unternehmens erfassen")));
    }

    @Test
    void settingsPagesShareOneNavigationEntryAndTabs() throws Exception {
        for (var path : new String[] {"/company", "/company/tax-profiles", "/text-templates", "/units", "/numbering"}) {
            var html = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(html).contains("Einstellungen").contains("href=\"/text-templates\"").contains("href=\"/numbering\"");
            assertThat(html).containsOnlyOnce("class=\"nav-item active\"");
            assertThat(html).containsOnlyOnce("aria-current=\"page\"");
            assertThat(html).contains("<a class=\"brand\" href=\"/\"");
        }
    }

    @Test
    void validationRulesAreShownInPlainLanguageAndUnknownOnesByTheirId() throws Exception {
        mvc.perform(get("/units")
                        .flashAttr("error", "einvoice.error.validation")
                        .flashAttr("errorDetails", List.of("BR-CO-26", "BR-XYZ-99")))
                .andExpect(content().string(containsString("Dem Verkäufer fehlt eine Kennung")))
                .andExpect(content().string(containsString("Die Prüfregel BR-XYZ-99 ist nicht erfüllt.")));
    }

    @Test
    void categoryReasonsCanBeEditedOnTheTaxProfilePage() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));

        mvc.perform(get("/company/tax-profiles"))
                .andExpect(content().string(containsString("Befreiungsgründe je Steuerkategorie")))
                .andExpect(content().string(containsString("§ 13b UStG")));

        mvc.perform(post("/company/tax-profiles/reasons").param("reason.E", "Steuerfrei nach Vertrag"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/company/tax-profiles"))
                .andExpect(content().string(containsString("Steuerfrei nach Vertrag")));
    }

    @Test
    void taxProfileCanBeCreatedAndAppearsInSidebar() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));

        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2020-01-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/company/tax-profiles"));

        mvc.perform(get("/company/tax-profiles"))
                .andExpect(content().string(containsString("Aktuell gültig")))
                .andExpect(content().string(containsString("01.01.2020")));
        mvc.perform(get("/units"))
                .andExpect(content().string(containsString("§ 19 UStG")));
    }

    @Test
    void taxProfileStartingBeforeLatestShowsRuleError() throws Exception {
        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));
        mvc.perform(post("/company/tax-profiles").param("regime", "SMALL_BUSINESS").param("validFrom", "2026-01-01"));

        mvc.perform(post("/company/tax-profiles").param("regime", "REGULAR").param("validFrom", "2025-01-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nach dem Beginn")))
                .andExpect(content().string(containsString("Neues Steuerprofil")));
    }

    @Test
    void newTaxProfileHasItsOwnPageAndTheListLinksToIt() throws Exception {
        mvc.perform(get("/company/tax-profiles/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/company/tax-profiles"));

        mvc.perform(post("/company").param("name", "Beispiel").param("country", "DE").param("paymentDays", "14").param("offerValidDays", "30"));

        mvc.perform(get("/company/tax-profiles"))
                .andExpect(content().string(containsString("Besteuerung im Zeitverlauf")))
                .andExpect(content().string(containsString("href=\"/company/tax-profiles/new\"")))
                .andExpect(content().string(containsString("<details class=\"card collapsible\">")))
                .andExpect(content().string(not(containsString("So wirkt sich das aus"))));
        mvc.perform(get("/company/tax-profiles/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("So wirkt sich das aus")))
                .andExpect(content().string(containsString("Zurück zu den Steuerprofilen")));
    }

    @Test
    void previewFragmentShowsTaxForRegularTaxation() throws Exception {
        mvc.perform(get("/company/tax-profiles/preview").param("regime", "REGULAR").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("190,00 €")))
                .andExpect(content().string(containsString("1.190,00 €")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void unitCanBeAddedAndDuplicateCodeIsReported() throws Exception {
        mvc.perform(post("/units").param("code", "Wo").param("name", "Woche").param("uneceCode", "WEE"))
                .andExpect(redirectedUrl("/units"))
                .andExpect(flash().attribute("message", "unit.created"));

        mvc.perform(post("/units").param("code", "Wo").param("name", "Woche").param("uneceCode", "WEE"))
                .andExpect(flash().attribute("error", "unit.error.codeExists"));

        mvc.perform(get("/units"))
                .andExpect(content().string(containsString("Woche")));
    }

    @Test
    void numberPatternCanBeChangedAndInvalidPatternIsReported() throws Exception {
        mvc.perform(post("/numbering/INVOICE").param("pattern", "R-{jahr}-{####}"))
                .andExpect(flash().attribute("message", "numbering.saved"));

        mvc.perform(post("/numbering/INVOICE").param("pattern", "R-{jahr}"))
                .andExpect(flash().attribute("error", "numbering.error.invalid"));

        mvc.perform(get("/numbering"))
                .andExpect(content().string(containsString("R-{jahr}-{####}")));
    }
}
