package de.cgoit.faktura.mcp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.security.UserInput;
import de.cgoit.faktura.security.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With sign-in (mode FORM) only an administrator may manage the MCP tokens: a normal user could otherwise mint a token
 * that creates drafts.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test-mcp-access?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.notification.enabled=false",
        "faktura.security.mode=FORM", "faktura.security.admin-user=Boss",
        "faktura.security.admin-password=correct horse battery", "faktura.mcp.enabled=true" })
@AutoConfigureMockMvc
class McpTokenAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserService users;

    @BeforeEach
    void setUp() {
        if (users.list().stream().noneMatch(user -> user.username().equals("fritz"))) {
            users.create(new UserInput("fritz", "Fritz", "a long enough password", false));
        }
    }

    @Test
    void aNormalUserCannotOpenOrChangeTheTokens() throws Exception {
        mvc.perform(get("/settings/mcp-tokens").with(user("fritz").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/settings/mcp-tokens/new").with(user("fritz").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/settings/mcp-tokens").with(user("fritz").roles("USER")).with(csrf())
                        .param("name", "x").param("scope", "DRAFT").param("validityDays", "30"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/settings/mcp-tokens/any/revoke").with(user("fritz").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCanOpenTheTokens() throws Exception {
        mvc.perform(get("/settings/mcp-tokens").with(user("boss").roles("USER", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void theMcpRouteNeedsATokenNotASession() throws Exception {
        mvc.perform(post("/mcp").contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
