package de.cgoit.faktura.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Mode PROXY_HEADER: the username comes from a header, but only from a trusted address.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test-proxy?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.notification.enabled=false",
        "faktura.security.mode=PROXY_HEADER", "server.forward-headers-strategy=framework", "faktura.security.header=X-Test-User", "faktura.security.name-header=X-Test-Name",
        "faktura.security.trusted-proxies=127.0.0.1/32,10.0.0.0/8", "faktura.security.admin-user=Boss" })
@AutoConfigureMockMvc
class ProxyHeaderModeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserService users;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM app_user").update();
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);

            return request;
        };
    }

    @Test
    void aRequestWithoutTheHeaderIsRefused() throws Exception {
        mvc.perform(get("/invoices"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTrustedRequestWithTheHeaderIsLetInAndTheUserIsCreated() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "Anna"))
                .andExpect(status().isOk());

        assertThat(users.list()).extracting(UserInfo::username).containsExactly("anna");
        assertThat(users.list().get(0).admin()).isFalse();
    }

    @Test
    void theDisplayNameOfTheProxyIsStoredAndFollowsChanges() throws Exception {
        // a proxy sends UTF-8, the servlet container hands it over as ISO-8859-1
        var sent = new String("Jörg Beispiel".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        mvc.perform(get("/invoices").header("X-Test-User", "erika").header("X-Test-Name", sent))
                .andExpect(status().isOk());

        assertThat(users.list().get(0).displayName()).isEqualTo("Jörg Beispiel");

        mvc.perform(get("/invoices").header("X-Test-User", "erika").header("X-Test-Name", "  "))
                .andExpect(status().isOk());

        assertThat(users.list().get(0).displayName()).isEqualTo("Jörg Beispiel");

        mvc.perform(get("/invoices").header("X-Test-User", "erika").header("X-Test-Name", "Jörg B."))
                .andExpect(status().isOk());

        assertThat(users.list().get(0).displayName()).isEqualTo("Jörg B.");
    }

    @Test
    void anOverlongDisplayNameIsCutInsteadOfRefusingTheRequest() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "anna").header("X-Test-Name", "x".repeat(300)))
                .andExpect(status().isOk());

        assertThat(users.list().get(0).displayName()).hasSize(200);
    }

    @Test
    void aRefusalIsLoggedWithTheReasonAndThePeerAddressButWithoutHeaderValues() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("de.cgoit.faktura.security.internal.ProxyHeaderFilter");
        var appender = new ListAppender<ILoggingEvent>();

        appender.start();
        logger.addAppender(appender);

        try {
            mvc.perform(get("/invoices").header("X-Test-User", "secret-name").with(from("203.0.113.77")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/invoices").with(from("10.9.9.9")))
                    .andExpect(status().isForbidden());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("trusted-proxies") && message.contains("203.0.113.77"))
                .anyMatch(message -> message.contains("X-Test-User missing") && message.contains("10.9.9.9"))
                .noneMatch(message -> message.contains("secret-name"));
    }

    @Test
    void aForwardedForHeaderCannotMakeAnUntrustedAddressTrusted() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "boss").header("X-Forwarded-For", "10.1.1.1")
                        .header("Forwarded", "for=10.1.1.1").with(from("203.0.113.55")))
                .andExpect(status().isForbidden());

        assertThat(users.list()).isEmpty();
    }

    @Test
    void aVisitThroughTheProxyRecordsTheLastLoginOncePerInterval() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "anna"))
                .andExpect(status().isOk());

        var first = lastLogin("anna");

        assertThat(first).isNotNull();

        mvc.perform(get("/invoices").header("X-Test-User", "anna"))
                .andExpect(status().isOk());

        assertThat(lastLogin("anna")).isEqualTo(first);

        jdbc.sql("UPDATE app_user SET last_login_at = '2020-01-01T10:00:00Z' WHERE username = 'anna'").update();
        mvc.perform(get("/invoices").header("X-Test-User", "anna"))
                .andExpect(status().isOk());

        assertThat(lastLogin("anna")).isNotEqualTo("2020-01-01T10:00:00Z").isNotNull();
    }

    private String lastLogin(String username) {
        return jdbc.sql("SELECT last_login_at FROM app_user WHERE username = :username")
                .param("username", username)
                .query(String.class)
                .single();
    }

    @Test
    void aRangeOfTrustedAddressesIsHonoured() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "anna").with(from("10.4.5.6")))
                .andExpect(status().isOk());
    }

    @Test
    void anUntrustedAddressCannotSetTheHeader() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "boss").with(from("203.0.113.9")))
                .andExpect(status().isForbidden());

        assertThat(users.list()).isEmpty();
    }

    @Test
    void theConfiguredAdministratorMayManageUsersAndOthersMayNot() throws Exception {
        mvc.perform(get("/settings/users").header("X-Test-User", "Boss"))
                .andExpect(status().isOk());
        mvc.perform(get("/settings/users").header("X-Test-User", "anna"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDeactivatedUserIsRefused() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "emil"))
                .andExpect(status().isOk());
        mvc.perform(get("/invoices").header("X-Test-User", "boss"))
                .andExpect(status().isOk());

        var emil = users.list().stream()
                .filter(user -> user.username().equals("emil"))
                .findFirst()
                .orElseThrow();

        users.setActive(emil.id(), false);

        mvc.perform(get("/invoices").header("X-Test-User", "emil"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSameUserSeenTwiceIsCreatedOnce() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "Anna"))
                .andExpect(status().isOk());
        mvc.perform(get("/invoices").header("X-Test-User", "anna"))
                .andExpect(status().isOk());

        assertThat(users.list()).hasSize(1);
    }

    @Test
    void aNameWithCharactersNoUserMayHaveIsRefused() throws Exception {
        mvc.perform(get("/invoices").header("X-Test-User", "a b<script>"))
                .andExpect(status().isForbidden());

        assertThat(users.list()).isEmpty();
    }

    @Test
    void healthStaysOpenWithoutTheHeader() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void thereIsNoPasswordFunction() throws Exception {
        mvc.perform(get("/account/password").header("X-Test-User", "anna"))
                .andExpect(status().isNotFound());
    }
}
