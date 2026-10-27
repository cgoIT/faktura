package de.cgoit.faktura.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Token administration: only the hash is stored, the token is shown once, validity and revocation hold, and creating
 * and revoking are audited.
 */
@SqliteTest
@TestPropertySource(properties = { "faktura.mcp.enabled=true", "faktura.security.mode=FORM",
        "faktura.security.admin-user=boss", "faktura.security.admin-password=correct horse battery" })
class McpTokenAdminTest {

    private static final RequestPostProcessor ADMIN = user("boss").roles("ADMIN");

    @Autowired
    private McpTokenService tokens;

    @Autowired
    private AuditService audit;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM mcp_token").update();
    }

    @Test
    void aCreatedTokenIsStoredOnlyAsHashAndExpiresOnTheChosenDay() {
        var created = tokens.create("  Claude Desktop ", McpScope.DRAFT, 30);
        var stored = jdbc.sql("SELECT token_hash, token_prefix FROM mcp_token")
                .query((rs, row) -> rs.getString(1) + "|" + rs.getString(2))
                .single();

        assertThat(created.token()).startsWith("fkt_").hasSizeGreaterThanOrEqualTo(32);
        assertThat(stored).doesNotContain(created.token());
        assertThat(stored).endsWith("|" + created.token().substring(0, 8));
        assertThat(created.info().name()).isEqualTo("Claude Desktop");
        assertThat(created.info().expiresOn()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(created.info().status()).isEqualTo(McpTokenStatus.ACTIVE);
        assertThat(created.toString()).doesNotContain(created.token());
    }

    @Test
    void aTokenWithoutEndDateNeverExpires() {
        var created = tokens.create("Forever", McpScope.READ, null);

        jdbc.sql("UPDATE mcp_token SET created_at = '2000-01-01T00:00:00Z'").update();

        assertThat(created.info().expiresOn()).isNull();
        assertThat(created.info().status()).isEqualTo(McpTokenStatus.ACTIVE);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mcp_token WHERE expires_on IS NULL")
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void anInvalidNameScopeOrValidityIsRefused() {
        assertThatThrownBy(() -> tokens.create(" ", McpScope.READ, 30)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> tokens.create("x".repeat(91), McpScope.READ, 30))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> tokens.create("A", null, 30)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> tokens.create("A", McpScope.READ, 7)).isInstanceOf(RuleViolationException.class);
        assertThat(tokens.list()).isEmpty();
    }

    @Test
    void revokingTakesEffectOnceAndIsAudited() {
        var info = tokens.create("A", McpScope.READ, 30).info();

        tokens.revoke(info.id());

        assertThat(tokens.list().getFirst().status()).isEqualTo(McpTokenStatus.REVOKED);
        assertThat(audit.actionsOf(AuditObjectType.MCP_TOKEN, info.id()))
                .containsExactly(AuditAction.MCP_TOKEN_CREATE, AuditAction.MCP_TOKEN_REVOKE);
        assertThatThrownBy(() -> tokens.revoke(info.id())).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void theTokenIsShownOnceInTheAnswerToCreating() throws Exception {
        var body = mvc.perform(post("/settings/mcp-tokens").with(ADMIN).param("name", "Desktop").param("scope", "DRAFT")
                        .param("validityDays", "90"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fkt_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var token = body.substring(body.indexOf("fkt_"), body.indexOf("fkt_") + 68);

        mvc.perform(get("/settings/mcp-tokens").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Desktop")))
                .andExpect(content().string(not(containsString(token))));
    }

    @Test
    void anEmptyListSaysSoAndTheFormOffersUnlimited() throws Exception {
        mvc.perform(get("/settings/mcp-tokens").with(ADMIN))
                .andExpect(content().string(containsString("Es sind noch keine Tokens erstellt.")))
                .andExpect(content().string(not(containsString("<table"))));
        mvc.perform(get("/settings/mcp-tokens/new").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unbegrenzt")));
        mvc.perform(post("/settings/mcp-tokens").with(ADMIN).param("name", "Forever").param("scope", "READ")
                        .param("validityDays", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fkt_")))
                .andExpect(content().string(containsString("Unbegrenzt")));
    }

    @Test
    void thePageNamesAddressHeaderAndAClipboardButton() throws Exception {
        mvc.perform(get("/settings/mcp-tokens").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http://localhost/mcp")))
                .andExpect(content().string(containsString("X-API-Key")))
                .andExpect(content().string(containsString("data-copy=\"mcp-url\"")))
                .andExpect(content().string(containsString("claude mcp add --transport http faktura")));
    }

    @Test
    void revokingFromThePageRedirectsBackToTheList() throws Exception {
        var info = tokens.create("A", McpScope.READ, 30).info();

        mvc.perform(post("/settings/mcp-tokens/" + info.id() + "/revoke").with(ADMIN))
                .andExpect(redirectedUrl("/settings/mcp-tokens"));

        assertThat(tokens.list().getFirst().status()).isEqualTo(McpTokenStatus.REVOKED);
    }
}
