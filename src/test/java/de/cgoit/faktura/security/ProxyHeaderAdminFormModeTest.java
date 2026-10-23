package de.cgoit.faktura.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Mode PROXY_HEADER_ADMIN_FORM: everybody comes in through the proxy header, the administrator switches to "admin mode"
 * with a password and leaves it again.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test-admin-form?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.notification.enabled=false",
        "faktura.security.mode=PROXY_HEADER_ADMIN_FORM", "server.forward-headers-strategy=framework",
        "faktura.security.header=X-Test-User", "faktura.security.trusted-proxies=127.0.0.1/32",
        "faktura.security.admin-user=Admin", "faktura.security.admin-password=correct horse battery" })
@AutoConfigureMockMvc
class ProxyHeaderAdminFormModeTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserService users;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM app_user WHERE username <> 'admin'").update();
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);

            return request;
        };
    }

    private MockHttpSession adminMode(String proxyUser) throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).header("X-Test-User", proxyUser)
                        .param("username", "admin").param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void theAdministratorIsCreatedWithAPasswordAtStart() {
        var admin = users.list().stream()
                .filter(user -> user.username().equals("admin"))
                .findFirst()
                .orElseThrow();

        assertThat(admin.admin()).isTrue();
        assertThat(admin.passwordSet()).isTrue();
    }

    @Test
    void aProxyUserIsLetInButIsNotAnAdministratorAndSeesTheLinkToAdminMode() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "erika"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login\"")))
                .andExpect(content().string(not(containsString("Admin-Modus beenden"))));
        mvc.perform(get("/settings/users").header("X-Test-User", "erika"))
                .andExpect(status().isForbidden());

        assertThat(users.list()).extracting(UserInfo::username).contains("erika");
    }

    @Test
    void aRequestWithoutHeaderOrFromAnUntrustedAddressIsRefusedEvenForTheSignInForm() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/login").with(from("203.0.113.9")).header("X-Test-User", "erika"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).with(from("203.0.113.9")).header("X-Test-User", "erika")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .param("username", "admin").param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void theNameOfTheAdministratorCannotBeUsedThroughTheHeader() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "Admin"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/settings/users").header("X-Test-User", "admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAccountWithAPasswordCannotSignInThroughTheHeader() throws Exception {
        users.create(new UserInput("form-only", null, "yet another long password", false));

        mvc.perform(get("/invoices").header("X-Test-User", "form-only"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminModeIsEnteredWithThePasswordAndWinsOverTheHeaderUntilItIsLeft() throws Exception {
        mvc.perform(get("/login").header("X-Test-User", "erika"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"password\"")));

        var session = adminMode("erika");

        mvc.perform(get("/settings/users").header("X-Test-User", "erika").session(session))
                .andExpect(status().isOk());
        mvc.perform(get("/settings/users").header("X-Test-User", "erika").session(session))
                .andExpect(content().string(containsString("Admin-Modus beenden")))
                .andExpect(content().string(containsString("admin")));

        mvc.perform(post("/logout").with(csrf()).header("X-Test-User", "erika").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/settings/users").header("X-Test-User", "erika").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/invoices").header("X-Test-User", "erika"))
                .andExpect(status().isOk());
    }

    @Test
    void adminModeShowsOnlyTheUserAdministrationAndSendsEverythingElseThere() throws Exception {
        var session = adminMode("erika");

        mvc.perform(get("/settings/users").header("X-Test-User", "erika").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/invoices\""))))
                .andExpect(content().string(not(containsString("href=\"/customers\""))))
                .andExpect(content().string(not(containsString("href=\"/company\""))))
                .andExpect(content().string(containsString("href=\"/settings/users\"")));

        for (var path : new String[] { "/", "/invoices", "/customers", "/company", "/dunning", "/mail-templates" }) {
            mvc.perform(get(path).header("X-Test-User", "erika").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/settings/users"));
        }

        mvc.perform(post("/invoices").with(csrf()).header("X-Test-User", "erika").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/account/password").header("X-Test-User", "erika").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void signingInToAdminModeLeadsToTheUserAdministration() throws Exception {
        mvc.perform(post("/login").with(csrf()).header("X-Test-User", "erika")
                        .param("username", "admin").param("password", PASSWORD))
                .andExpect(redirectedUrl("/settings/users"));
    }

    @Test
    void proxyUsersHaveNoAccountPageAndNoUserTabInTheSettings() throws Exception {
        mvc.perform(get("/account/password").header("X-Test-User", "erika"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/company").header("X-Test-User", "erika"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/settings/users\""))));
    }

    @Test
    void aWrongPasswordDoesNotEnterAdminModeAndTheProxyUserStaysSignedIn() throws Exception {
        mvc.perform(post("/login").with(csrf()).header("X-Test-User", "erika")
                        .param("username", "admin").param("password", "wrong wrong wrong"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/settings/users").header("X-Test-User", "erika"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/invoices").header("X-Test-User", "erika"))
                .andExpect(status().isOk());
    }

    @Test
    void aProxyUserCannotSignInToTheFormWithoutAPassword() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "erika"))
                .andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).header("X-Test-User", "erika")
                        .param("username", "erika").param("password", ""))
                .andExpect(redirectedUrl("/login?error"));
    }
}
