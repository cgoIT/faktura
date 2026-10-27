package de.cgoit.faktura.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.jayway.jsonpath.JsonPath;
import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The MCP endpoint: only the bearer token lets a request in, the tools read and create drafts, and a draft is
 * recorded with the actor {@code mcp:<token name>}.
 */
@SqliteTest
@TestPropertySource(properties = {
        "faktura.mcp.enabled=true",
        "faktura.mcp.token=0123456789abcdef0123456789abcdef-test" })
class McpServerTest {

    private static final String EMERGENCY = "0123456789abcdef0123456789abcdef-test";
    private static final String HOUR = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private McpTokenService tokenService;

    private MockMvc mvc;
    private String customerId;
    private String token;

    @BeforeEach
    void setUp() {
        cleanUp();
        // own MockMvc: the shared one adds a CSRF token to every request, which would hide a missing exception
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category)
                VALUES ('p1', 'c1', '2020-01-01', 'SMALL_BUSINESS', 0, 'E')
                """).update();
        customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null)).id();
        catalog.create(new CatalogItemInput("Webseiten-Programmierung", null, HOUR, 8500, null));
        token = tokenService.create("Test", McpScope.DRAFT, 90).token();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        jdbc.sql("DELETE FROM mcp_token").update();
    }

    @Test
    void aRequestWithoutOrWithAWrongTokenIsRefused() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("ping", "{}")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization", "Bearer wrong").contentType(MediaType.APPLICATION_JSON)
                .content(rpc("ping", "{}")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenAlsoWorksAsAPlainHeaderValue() throws Exception {
        mvc.perform(post("/mcp").header("X-API-Key", token).contentType(MediaType.APPLICATION_JSON)
                .content(rpc("ping", "{}")))
                .andExpect(status().isOk());
        mvc.perform(post("/mcp").header("X-API-Key", "wrong").contentType(MediaType.APPLICATION_JSON)
                .content(rpc("ping", "{}")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedTokenStopsWorkingAtOnce() throws Exception {
        call("ping", "{}").andExpect(status().isOk());

        tokenService.revoke(tokenService.list().getFirst().id());

        call("ping", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRefusedButTheLastDayStillCounts() throws Exception {
        jdbc.sql("UPDATE mcp_token SET expires_on = :day")
                .param("day", LocalDate.now().toString())
                .update();
        call("ping", "{}").andExpect(status().isOk());

        jdbc.sql("UPDATE mcp_token SET expires_on = :day")
                .param("day", LocalDate.now().minusDays(1).toString())
                .update();
        call("ping", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutEndDateWorks() throws Exception {
        var forever = tokenService.create("Forever", McpScope.READ, null).token();

        mvc.perform(post("/mcp").header("Authorization", "Bearer " + forever).contentType(MediaType.APPLICATION_JSON)
                .content(rpc("ping", "{}")))
                .andExpect(status().isOk());
    }

    @Test
    void aReadTokenSeesNoDraftToolAndCannotCreateADraft() throws Exception {
        var read = tokenService.create("Reader", McpScope.READ, 30).token();

        mvc.perform(post("/mcp").header("Authorization", "Bearer " + read).contentType(MediaType.APPLICATION_JSON)
                .content(rpc("tools/list", "{}")))
                .andExpect(jsonPath("$.result.tools[*].name").value(not(hasItem("create_invoice_draft"))));
        var arguments = """
                {"name":"create_invoice_draft","arguments":{"customerId":"%s",
                 "items":[{"description":"Anfahrt","unitPrice":"40"}]}}
                """.formatted(customerId);

        mvc.perform(post("/mcp").header("Authorization", "Bearer " + read).contentType(MediaType.APPLICATION_JSON)
                .content(rpc("tools/call", arguments)))
                .andExpect(jsonPath("$.result.isError").value(true));

        assertThat(invoices.list("", DocumentStatus.DRAFT)).isEmpty();
    }

    @Test
    void theTokenFromTheEnvironmentOnlyReads() throws Exception {
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + EMERGENCY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpc("tools/list", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name").value(not(hasItem("create_offer_draft"))));
    }

    @Test
    void aUseIsRecordedAtMostOncePerMinute() throws Exception {
        call("ping", "{}");

        var first = jdbc.sql("SELECT last_used_at FROM mcp_token")
                .query(String.class)
                .single();

        call("ping", "{}");

        assertThat(first).isNotNull();
        assertThat(jdbc.sql("SELECT last_used_at FROM mcp_token")
                .query(String.class)
                .single()).isEqualTo(first);
    }

    @Test
    void initializeAnswersWithTheRequestedProtocolVersion() throws Exception {
        call("initialize", "{\"protocolVersion\":\"2025-03-26\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("faktura"))
                .andExpect(jsonPath("$.result.instructions").isNotEmpty());
    }

    @Test
    void aNotificationGetsNoAnswerBody() throws Exception {
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mvc.perform(get("/mcp").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void theToolsAreReadingAndDraftingOnly() throws Exception {
        call("tools/list", "{}")
                .andExpect(jsonPath("$.result.tools[*].name").value(containsInAnyOrder(
                        "search_customers", "search_catalog", "list_unpaid_invoices", "get_invoice",
                        "list_dunning_suggestions", "list_expiring_offers", "create_invoice_draft",
                        "create_offer_draft")));
    }

    @Test
    void anUnknownToolIsAProtocolError() throws Exception {
        call("tools/call", "{\"name\":\"finalize_invoice\",\"arguments\":{}}")
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void anInvoiceDraftFromTheCatalogCarriesTheRequestedQuantityAndTheActorMcp() throws Exception {
        var customer = text(tool("search_customers", "{\"query\":\"müller\"}"), "$.rows[0].id");
        var item = text(tool("search_catalog", "{\"query\":\"webseiten\"}"), "$.rows[0].id");

        assertThat(customer).isEqualTo(customerId);

        var result = tool("create_invoice_draft", """
                {"customerId":"%s","subject":"Webseite","items":[{"catalogItemId":"%s","quantity":"10"}]}
                """.formatted(customer, item));
        var id = text(result, "$.id");
        var draft = invoices.find(id).orElseThrow();

        assertThat(text(result, "$.grossEur")).isEqualTo("850.00");
        assertThat(draft.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(draft.number()).isNull();
        assertThat(draft.createdBy()).isEqualTo("mcp:Test");
        assertThat(draft.items()).singleElement()
                .satisfies(row -> assertThat(row.quantityThousandths()).isEqualTo(10_000));
    }

    @Test
    void anInvoiceDraftTakesPaymentTermTotalDiscountAndDesignation() throws Exception {
        var result = tool("create_invoice_draft", """
                {"customerId":"%s","subject":"Erstellt mit MCP","paymentDays":25,"totalDiscountPercent":"5",
                 "items":[{"description":"Anfahrt","unitPrice":"100"}]}
                """.formatted(customerId));
        var draft = invoices.find(text(result, "$.id")).orElseThrow();

        assertThat(text(result, "$.grossEur")).isEqualTo("95.00");
        assertThat(text(result, "$.totalDiscountPercent")).isEqualTo("5");
        assertThat(draft.subject()).isEqualTo("Erstellt mit MCP");
        assertThat(draft.dueDate()).isEqualTo(draft.issueDate().plusDays(25));
    }

    @Test
    void aFreeTextItemNeedsAPrice() throws Exception {
        tool("create_invoice_draft", """
                {"customerId":"%s","items":[{"description":"Anfahrt"}]}
                """.formatted(customerId));

        assertThat(invoices.list("", DocumentStatus.DRAFT)).isEmpty();
    }

    @Test
    void numbersAreBoundedPlainDecimals() throws Exception {
        for (var quantity : new String[] { "1E999999999", "1,000", "abc" }) {
            call("tools/call", """
                    {"name":"create_invoice_draft","arguments":{"customerId":"%s","items":
                    [{"description":"Anfahrt","unitPrice":"40","quantity":"%s"}]}}
                    """.formatted(customerId, quantity))
                    .andExpect(jsonPath("$.result.isError").value(true));
        }

        assertThat(invoices.list("", DocumentStatus.DRAFT)).isEmpty();
    }

    @Test
    void aBrokenBodyIsAParseError() throws Exception {
        mvc.perform(post("/mcp").header("Authorization", "bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"jsonrpc\":\"2.0\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void aBrokenRuleIsAnErrorResultTheAssistantCanRead() throws Exception {
        call("tools/call", """
                {"name":"create_invoice_draft","arguments":{"customerId":"unknown","items":
                [{"description":"Anfahrt","unitPrice":"40"}]}}
                """)
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").isNotEmpty());
        assertThat(invoices.list("", DocumentStatus.DRAFT)).isEmpty();
    }

    @Test
    void unpaidInvoicesShowTheOpenAmountAndOverdueDays() throws Exception {
        var due = LocalDate.now().minusDays(5);

        jdbc.sql("""
                INSERT INTO document (id, type, status, number, customer_id, issue_date, due_date, gross_total_cents,
                                      created_at, updated_at)
                VALUES ('d1', 'INVOICE', 'FINAL', 'RE1', :customer, '2026-01-01', :due, 10000,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
                """)
                .param("customer", customerId)
                .param("due", due.toString())
                .update();

        var result = tool("list_unpaid_invoices", "{\"onlyOverdue\":true}");

        assertThat(text(result, "$.rows[0].openEur")).isEqualTo("100.00");
        assertThat(text(result, "$.rows[0].overdue")).isEqualTo("true");
    }

    private ResultActions call(String method, String params) throws Exception {
        return mvc.perform(post("/mcp").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rpc(method, params)));
    }

    /**
     * Calls a tool and returns the JSON text it answered.
     */
    private String tool(String name, String arguments) throws Exception {
        var body = call("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.result.content[0].text");
    }

    private static String text(String json, String path) {
        return String.valueOf((Object) JsonPath.read(json, path));
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }
}
