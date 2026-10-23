package de.cgoit.faktura.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.cgoit.faktura.shared.CurrentUser;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mode FORM: sign-in page, lock after failures, administrator rights and the rules for the last administrator.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test-form?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.notification.enabled=false",
        "faktura.security.mode=FORM", "faktura.security.admin-user=Boss",
        "faktura.security.admin-password=correct horse battery", "faktura.security.max-failures=3" })
@AutoConfigureMockMvc
class FormModeTest {

    private static final String PASSWORD = "another long password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserService users;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM app_user WHERE username <> 'boss'").update();
        jdbc.sql("UPDATE app_user SET admin = 1, active = 1 WHERE username = 'boss'").update();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFirstStartCreatesTheAdministratorFromTheSettings() {
        var boss = users.list().stream()
                .filter(user -> user.username().equals("boss"))
                .findFirst()
                .orElseThrow();

        assertThat(boss.admin()).isTrue();
        assertThat(boss.active()).isTrue();
    }

    @Test
    void pagesNeedASignIn() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void theSignInPageAndTheStylesAreOpen() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"password\"")));
        mvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
        mvc.perform(get("/images/logo.svg"))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsOpenForTheContainerCheck() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void aSignInWithTheRightPasswordWorksAndRecordsTheTime() throws Exception {
        mvc.perform(formLogin().user("Boss").password("correct horse battery"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(jdbc.sql("SELECT last_login_at FROM app_user WHERE username = 'boss'")
                .query(String.class)
                .single()).isNotNull();
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        mvc.perform(formLogin().user("boss").password("wrong"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void theAccountIsLockedAfterTooManyFailuresEvenForTheRightPassword() throws Exception {
        users.create(new UserInput("anna", "Anna", PASSWORD, false));

        for (var attempt = 0; attempt < 3; attempt++) {
            mvc.perform(formLogin().user("anna").password("wrong"))
                    .andExpect(redirectedUrl("/login?error"));
        }

        mvc.perform(formLogin().user("anna").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anInactiveUserCannotSignIn() throws Exception {
        var anna = users.create(new UserInput("emil", null, PASSWORD, false));

        users.setActive(anna.id(), false);

        mvc.perform(formLogin().user("emil").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void onlyAnAdministratorSeesTheUsers() throws Exception {
        users.create(new UserInput("fritz", null, PASSWORD, false));

        mvc.perform(get("/settings/users").with(user("fritz").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/settings/users").with(user("boss").roles("USER", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fritz")));
    }

    @Test
    void theButtonsOfTheOnlyAdministratorAreDisabledAndTheLastSignInIsLocalTime() throws Exception {
        jdbc.sql("UPDATE app_user SET last_login_at = '2026-10-23T09:09:00Z' WHERE username = 'boss'").update();

        var page = mvc.perform(get("/settings/users").with(user("boss").roles("USER", "ADMIN")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page).contains("disabled=\"disabled\"");
        assertThat(page).containsPattern("23\\.10\\.2026 \\d{2}:09");
        assertThat(page).doesNotContain("2026-10-23T");

        users.create(new UserInput("nora", null, PASSWORD, true));

        assertThat(mvc.perform(get("/settings/users").with(user("boss").roles("USER", "ADMIN")))
                .andReturn()
                .getResponse()
                .getContentAsString()).doesNotContain("disabled=\"disabled\"");
    }

    @Test
    void theLastAdministratorCannotBeDeactivatedOrDemoted() {
        var boss = users.list().get(0);

        assertThatThrownBy(() -> users.setActive(boss.id(), false))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("user.error.lastAdmin");
        assertThatThrownBy(() -> users.setAdmin(boss.id(), false))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("user.error.lastAdmin");
    }

    @Test
    void anAdministratorCanBeDemotedWhenAnotherOneIsLeft() {
        var second = users.create(new UserInput("gerda", null, PASSWORD, true));

        users.setAdmin(second.id(), false);

        assertThat(users.list()).filteredOn(UserInfo::admin).hasSize(1);
    }

    @Test
    void userNamesAreUniqueIgnoringCaseAndPasswordsHaveAMinimumLength() {
        users.create(new UserInput("hanna", null, PASSWORD, false));

        assertThatThrownBy(() -> users.create(new UserInput("Hanna", null, PASSWORD, false)))
                .hasMessage("user.error.exists");
        assertThatThrownBy(() -> users.create(new UserInput("ida", null, "short", false)))
                .hasMessage("user.error.passwordTooShort");
        assertThatThrownBy(() -> users.create(new UserInput("with space", null, PASSWORD, false)))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void anUserChangesTheOwnPasswordOnlyWithTheCurrentOne() throws Exception {
        users.create(new UserInput("jonas", null, PASSWORD, false));

        assertThatThrownBy(() -> users.changeOwnPassword("jonas", "wrong", "a brand new password"))
                .hasMessage("user.error.wrongPassword");

        users.changeOwnPassword("jonas", PASSWORD, "a brand new password");

        mvc.perform(formLogin().user("jonas").password("a brand new password"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void deactivatingAUserEndsTheirOpenSessionAtTheNextRequest() throws Exception {
        var emil = users.create(new UserInput("kurt", null, PASSWORD, false));
        var session = mvc.perform(formLogin().user("kurt").password(PASSWORD))
                .andReturn()
                .getRequest()
                .getSession();

        mvc.perform(get("/invoices").session((MockHttpSession) session))
                .andExpect(status().isOk());

        users.setActive(emil.id(), false);

        mvc.perform(get("/invoices").session((MockHttpSession) session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void demotingAnAdministratorEndsTheirSessionToo() throws Exception {
        var second = users.create(new UserInput("lena", null, PASSWORD, true));
        var session = (MockHttpSession) mvc.perform(formLogin().user("lena").password(PASSWORD))
                .andReturn()
                .getRequest()
                .getSession();

        mvc.perform(get("/settings/users").session(session))
                .andExpect(status().isOk());

        users.setAdmin(second.id(), false);

        mvc.perform(get("/settings/users").session(session))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void unknownNamesDoNotCountAndDoNotDisturbTheLockOfARealAccount() throws Exception {
        users.create(new UserInput("mira", null, PASSWORD, false));

        for (var attempt = 0; attempt < 50; attempt++) {
            mvc.perform(formLogin().user("nobody" + attempt).password("wrong"));
        }

        mvc.perform(formLogin().user("mira").password("wrong"));
        mvc.perform(formLogin().user("mira").password("wrong"));
        mvc.perform(formLogin().user("mira").password("wrong"));
        mvc.perform(formLogin().user("mira").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anAdministratorSetsAPasswordOnlyWhenBothEntriesMatch() throws Exception {
        var oskar = users.create(new UserInput("oskar", null, PASSWORD, false));
        var admin = user("boss").roles("USER", "ADMIN");

        mvc.perform(post("/settings/users/" + oskar.id() + "/password").param("password", "a brand new password")
                        .param("passwordRepeat", "a brand new passwort").with(admin).with(csrf()))
                .andExpect(flash().attribute("error", "user.error.passwordMismatch"));
        mvc.perform(formLogin().user("oskar").password(PASSWORD))
                .andExpect(redirectedUrl("/"));

        mvc.perform(post("/settings/users/" + oskar.id() + "/password").param("password", "a brand new password")
                        .param("passwordRepeat", "a brand new password").with(admin).with(csrf()))
                .andExpect(flash().attribute("message", "user.passwordChanged"));
        mvc.perform(formLogin().user("oskar").password("a brand new password"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aNewUserIsCreatedOnItsOwnPageAndAMistakeKeepsTheTypedValues() throws Exception {
        var admin = user("boss").roles("USER", "ADMIN");

        mvc.perform(get("/settings/users/new").with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"passwordRepeat\"")));
        mvc.perform(post("/settings/users").param("username", "quirin").param("displayName", "Quirin")
                        .param("password", PASSWORD).param("passwordRepeat", "nope").with(admin).with(csrf()))
                .andExpect(redirectedUrl("/settings/users/new"))
                .andExpect(flash().attribute("error", "user.error.passwordMismatch"))
                .andExpect(flash().attribute("username", "quirin"));
        assertThat(users.list()).extracting(UserInfo::username).doesNotContain("quirin");

        mvc.perform(post("/settings/users").param("username", "quirin").param("password", PASSWORD)
                        .param("passwordRepeat", PASSWORD).with(admin).with(csrf()))
                .andExpect(redirectedUrl("/settings/users"))
                .andExpect(flash().attribute("message", "user.created"));
        assertThat(users.list()).extracting(UserInfo::username).contains("quirin");
    }

    @Test
    void theOwnPasswordNeedsTheCurrentOneAndTwoEqualNewEntries() throws Exception {
        users.create(new UserInput("pia", null, PASSWORD, false));

        mvc.perform(post("/account/password").param("currentPassword", PASSWORD)
                        .param("newPassword", "a brand new password").param("newPasswordRepeat", "different")
                        .with(user("pia").roles("USER")).with(csrf()))
                .andExpect(flash().attribute("error", "user.error.passwordMismatch"));
        mvc.perform(post("/account/password").param("currentPassword", PASSWORD)
                        .param("newPassword", "a brand new password").param("newPasswordRepeat", "a brand new password")
                        .with(user("pia").roles("USER")).with(csrf()))
                .andExpect(flash().attribute("message", "account.passwordChanged"));
        mvc.perform(formLogin().user("pia").password("a brand new password"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void thePostOfTheFormNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/login").param("username", "boss").param("password", "correct horse battery"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/login").param("username", "boss").param("password", "correct horse battery").with(csrf()))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void theCurrentUserIsTheSignedInUserAndTheSystemOtherwise() {
        assertThat(currentUser.username()).isEqualTo(CurrentUser.SYSTEM);

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("boss",
                null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThat(currentUser.username()).isEqualTo("boss");
    }
}
