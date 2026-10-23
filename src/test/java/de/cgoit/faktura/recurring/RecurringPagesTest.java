package de.cgoit.faktura.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class RecurringPagesTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private RecurringPlanService plans;

    @Autowired
    private RecurringJobService job;

    @Autowired
    private InvoiceService invoices;

    private String customerId;

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

    @AfterEach
    void cleanUp() {
        TestArchive.clear();
        jdbc.sql("DELETE FROM recurring_run").update();
        jdbc.sql("DELETE FROM recurring_plan_item").update();
        jdbc.sql("DELETE FROM recurring_plan").update();
        jdbc.sql("DELETE FROM catalog_item").update();
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

    private PlanInfo plan() {
        var created = plans.create(new PlanInput(customerId, "Wartungsvertrag", RecurringInterval.QUARTER,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null))));

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0").update();

        return created;
    }

    @Test
    void catalogSearchOfThePlanFormFindsEntriesByNameOrDescription() throws Exception {
        for (var entry : new String[][] { { "T1", "Webentwicklung", "Stundensatz" }, { "T2", "Hosting", "Webhosting je Monat" },
                { "T3", "Beratung", "Tagessatz" } }) {
            jdbc.sql("""
                    INSERT INTO catalog_item (id, number, name, description, unit_id, unit_price_cents, active)
                    VALUES (:id, :number, :name, :description, '00000000-0000-0000-0000-000000000001', 1000, 1)
                    """)
                    .param("id", "id-" + entry[0])
                    .param("number", entry[0])
                    .param("name", entry[1])
                    .param("description", entry[2])
                    .update();
        }

        mvc.perform(get("/recurring/new"))
                .andExpect(content().string(containsString("data-catalog-search")))
                .andExpect(content().string(containsString("/recurring/catalog/search")));
        mvc.perform(post("/recurring/catalog/search").header("HX-Request", "true").param("catalogQuery", "web"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Webentwicklung")))
                .andExpect(content().string(containsString("Hosting")))
                .andExpect(content().string(not(containsString("Beratung"))))
                .andExpect(content().string(containsString("/recurring/items/add-catalog?catalogItemId=id-T1")))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void emptyListShowsTheHintAndNoBadge() throws Exception {
        mvc.perform(get("/recurring"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Entwürfe warten")))
                .andExpect(content().string(not(containsString("nav-badge"))))
                .andExpect(content().string(not(containsString("https://"))));
    }

    @Test
    void listsPendingDraftsWithBadgeAndLinkToTheEditor() throws Exception {
        plan();
        job.runDue(TODAY);

        var draftId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();

        mvc.perform(get("/recurring"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wartungsvertrag, 01.11.2026 – 31.01.2027")))
                .andExpect(content().string(containsString("450,00")))
                .andExpect(content().string(containsString("/invoices/" + draftId)))
                .andExpect(content().string(containsString("nav-badge")))
                .andExpect(content().string(containsString("Prüfen und finalisieren")));
        mvc.perform(get("/invoices/" + draftId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nav-badge")));
    }

    @Test
    void skippingFromThePageDeletesTheDraft() throws Exception {
        plan();
        job.runDue(TODAY);

        var runId = jdbc.sql("SELECT id FROM recurring_run")
                .query(String.class)
                .single();

        mvc.perform(post("/recurring/runs/" + runId + "/skip"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recurring"))
                .andExpect(flash().attribute("message", "recurring.skipped"));
        assertThat(invoices.list("", null)).isEmpty();
        assertThat(plans.pendingCount()).isZero();
    }

    @Test
    void createsAPlanFromTheForm() throws Exception {
        mvc.perform(post("/recurring")
                        .param("customerId", customerId)
                        .param("name", "Hosting")
                        .param("interval", "MONTH")
                        .param("anchorDate", "2026-11-28")
                        .param("leadDays", "14")
                        .param("status", "ACTIVE")
                        .param("items[0].description", "Webhosting")
                        .param("items[0].quantity", "1")
                        .param("items[0].unitCode", "Std")
                        .param("items[0].price", "120,00")
                        .param("items[0].discount", "")
                        .param("items[0].taxCategory", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recurring"))
                .andExpect(flash().attribute("message", "recurring.saved"));

        var created = plans.list()
                .getFirst();

        assertThat(created.name()).isEqualTo("Hosting");
        assertThat(created.netCents()).isEqualTo(12_000);
        assertThat(plans.find(created.id()).orElseThrow().items()).hasSize(1);
        mvc.perform(get("/recurring"))
                .andExpect(content().string(containsString("Hosting")))
                .andExpect(content().string(containsString("120,00")));
    }

    @Test
    void errorOfAnItemShowsAtTheFieldOfItsRow() throws Exception {
        var html = mvc.perform(post("/recurring")
                        .param("customerId", customerId)
                        .param("name", "Hosting")
                        .param("interval", "MONTH")
                        .param("anchorDate", "2026-11-28")
                        .param("leadDays", "14")
                        .param("status", "ACTIVE")
                        .param("items[0].description", "Webhosting")
                        .param("items[0].quantity", "1")
                        .param("items[0].price", "120,00")
                        .param("items[1].description", "Domain")
                        .param("items[1].quantity", "1")
                        .param("items[1].price", "abc"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var first = html.indexOf("name=\"items[0].description\"");
        var second = html.indexOf("name=\"items[1].description\"");
        var end = html.indexOf("</table>", second);

        assertThat(html.substring(first, second)).doesNotContain("field-error");
        assertThat(html.substring(second, end)).contains("field-error").contains("Bitte einen Betrag");
        assertThat(plans.list()).isEmpty();
    }

    @Test
    void invalidFormShowsTheErrorAndKeepsTheValues() throws Exception {
        mvc.perform(post("/recurring")
                        .param("customerId", customerId)
                        .param("name", "")
                        .param("interval", "MONTH")
                        .param("anchorDate", "2026-11-28")
                        .param("leadDays", "14")
                        .param("status", "ACTIVE")
                        .param("items[0].description", "Webhosting")
                        .param("items[0].quantity", "1")
                        .param("items[0].price", "120,00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Webhosting")))
                .andExpect(content().string(containsString("notice error")));
        assertThat(plans.list()).isEmpty();
    }

    @Test
    void formShowsThePlanAndThePeriodPreview() throws Exception {
        var created = plan();

        mvc.perform(get("/recurring/" + created.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wartungsvertrag")))
                .andExpect(content().string(containsString("Änderungen gelten nur für künftige Entwürfe")));
        mvc.perform(post("/recurring/preview")
                        .param("interval", "QUARTER")
                        .param("anchorDate", "2099-02-15"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("15.02.2099 – 14.05.2099")))
                .andExpect(content().string(containsString("15.05.2099 – 14.08.2099")));
        mvc.perform(get("/recurring/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemRowsCanBeAddedAndRemoved() throws Exception {
        mvc.perform(post("/recurring/items/add")
                        .param("items[0].description", "A")
                        .param("items[0].quantity", "1")
                        .param("items[0].price", "1,00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("items[1].description")));
        mvc.perform(post("/recurring/items/remove")
                        .param("index", "0")
                        .param("items[0].description", "A")
                        .param("items[0].quantity", "1")
                        .param("items[0].price", "1,00"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("items[0].description"))));
    }

    @Test
    void pausingAndResumingFromTheList() throws Exception {
        var created = plan();

        mvc.perform(post("/recurring/" + created.id() + "/status").param("status", "PAUSED"))
                .andExpect(status().is3xxRedirection());
        assertThat(plans.find(created.id()).orElseThrow().status()).isEqualTo(RecurringStatus.PAUSED);
        assertThat(job.runDue(TODAY)).isZero();
        mvc.perform(get("/recurring"))
                .andExpect(content().string(containsString("Pausiert")))
                .andExpect(content().string(containsString("Fortsetzen")));
    }

    @Test
    void runNowCreatesDraftsForToday() throws Exception {
        plans.create(new PlanInput(customerId, "Sofort", RecurringInterval.MONTH, LocalDate.now(), null, 14,
                RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Pflege", 1000, null, 10_000, 0, null, null))));

        mvc.perform(post("/recurring/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "recurring.run.created"));
        assertThat(plans.pendingCount()).isEqualTo(1);
    }
}
