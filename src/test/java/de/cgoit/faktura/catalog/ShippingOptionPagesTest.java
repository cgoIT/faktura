package de.cgoit.faktura.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class ShippingOptionPagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShippingOptionService options;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM shipping_option").update();
    }

    @Test
    void createdOptionAppearsInTheListAndOnlyActiveOnesAreOffered() throws Exception {
        mvc.perform(post("/settings/shipping").param("name", "Päckchen").param("amount", "4,95")
                        .param("taxCategory", "").param("taxRate", "0").param("sortOrder", "10").param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/shipping"));
        mvc.perform(post("/settings/shipping").param("name", "Kurier").param("amount", "14,90")
                        .param("taxCategory", "S").param("taxRate", "19").param("sortOrder", "20"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/settings/shipping"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Päckchen")))
                .andExpect(content().string(containsString("4,95")))
                .andExpect(content().string(containsString("Kurier")));

        assertThat(options.list()).hasSize(2);
        assertThat(options.listActive()).extracting(ShippingOptionInfo::name).containsExactly("Päckchen");
        assertThat(options.list().get(1).taxRateBp()).isEqualTo(1900);
    }

    @Test
    void invalidInputIsShownOnTheFormAndNothingIsStored() throws Exception {
        mvc.perform(post("/settings/shipping").param("name", " ").param("amount", "4,95").param("taxCategory", "")
                        .param("taxRate", "0").param("sortOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte ausfüllen.")));
        mvc.perform(post("/settings/shipping").param("name", "X").param("amount", "abc").param("taxCategory", "")
                        .param("taxRate", "0").param("sortOrder", "0"))
                .andExpect(status().isOk());

        assertThat(options.list()).isEmpty();
    }

    @Test
    void unknownTaxCategoryIsRejectedAndRateIsDroppedWithoutCategory() {
        assertThatThrownBy(() -> options.create(new ShippingOptionInput("X", 100, "QQ", 0, 0, true)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("catalog.error.taxCategory");

        var created = options.create(new ShippingOptionInput(" Paket ", 695, "", 1900, 0, true));

        assertThat(created.name()).isEqualTo("Paket");
        assertThat(created.taxCategory()).isNull();
        assertThat(created.taxRateBp()).isZero();
    }

    @Test
    void optionCanBeSwitchedOffButNeverDeleted() {
        var created = options.create(new ShippingOptionInput("Paket", 695, null, 0, 0, true));

        options.update(created.id(), new ShippingOptionInput("Paket", 695, null, 0, 0, false));

        assertThat(options.listActive()).isEmpty();
        assertThat(options.find(created.id())).isPresent();
    }
}
