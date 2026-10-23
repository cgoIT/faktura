package de.cgoit.faktura.textblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.SqliteTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class TextTemplatePagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM text_template WHERE id NOT LIKE '10000000-%'").update();
        jdbc.sql("UPDATE text_template SET is_default = 1 WHERE id LIKE '10000000-%'").update();
    }

    @Test
    void listShowsSeededTextsAndPlaceholdersAreExplained() throws Exception {
        mvc.perform(get("/text-templates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Textvorlagen")))
                .andExpect(content().string(containsString("Standard")));
        mvc.perform(get("/text-templates/new"))
                .andExpect(content().string(containsString("{anrede}")))
                .andExpect(content().string(containsString("{leistungszeitraum}")));
    }

    @Test
    void listGroupsByKindShowsTheScopeOfDefaultsAndOffersEditAndDelete() throws Exception {
        var html = mvc.perform(get("/text-templates"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html.indexOf(">Kopftexte<")).isPositive().isLessThan(html.indexOf(">Fußtexte<"));
        assertThat(html).contains("ich erlaube mir").contains("Ich bedanke mich").doesNotContain("Wir bedanken")
                .contains("Standard für Rechnungen")
                .contains("aria-label=\"Bearbeiten\"")
                .contains("aria-label=\"Löschen\"")
                .contains("/delete");
    }

    @Test
    void templateCanBeCreatedChangedAndDeleted() throws Exception {
        mvc.perform(post("/text-templates").param("scope", "INVOICE").param("kind", "FOOTER").param("name", "Kurz")
                        .param("body", "Danke, {kunde}!").param("makeDefault", "true"))
                .andExpect(redirectedUrl("/text-templates"));

        var id = jdbc.sql("SELECT id FROM text_template WHERE name = 'Kurz'").query(String.class).single();
        assertThat(jdbc.sql("SELECT is_default FROM text_template WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/text-templates/" + id).param("name", "Kürzer").param("body", "Danke!"))
                .andExpect(redirectedUrl("/text-templates"));
        assertThat(jdbc.sql("SELECT body FROM text_template WHERE id = :id").param("id", id).query(String.class)
                .single()).isEqualTo("Danke!");

        mvc.perform(post("/text-templates/" + id + "/delete"))
                .andExpect(redirectedUrl("/text-templates"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM text_template WHERE id = :id").param("id", id).query(Long.class)
                .single()).isZero();
    }

    @Test
    void unknownPlaceholderShowsTheErrorAndKeepsTheInput() throws Exception {
        mvc.perform(post("/text-templates").param("scope", "INVOICE").param("kind", "HEADER").param("name", "X")
                        .param("body", "Hallo {vorname}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unbekannten Platzhalter")))
                .andExpect(content().string(containsString("Hallo {vorname}")));
    }
}
