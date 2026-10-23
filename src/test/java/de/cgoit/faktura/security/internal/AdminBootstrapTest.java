package de.cgoit.faktura.security.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import de.cgoit.faktura.security.UserInput;
import de.cgoit.faktura.security.UserService;
import org.junit.jupiter.api.Test;

class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserService userService = mock(UserService.class);

    private AdminBootstrap bootstrap(SecurityMode mode, String adminUser, String adminPassword) {
        var properties = new SecurityProperties(mode, "X-User", "", List.of(), "default-src 'self'", 12, 5,
                Duration.ofMinutes(15), Duration.ofMinutes(30), adminUser, adminPassword);

        return new AdminBootstrap(properties, users, userService);
    }

    @Test
    void firstStartInModeFormWithoutAdministratorSettingsFailsWithAHint() {
        when(users.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap(SecurityMode.FORM, "", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY_ADMIN_USER");
    }

    @Test
    void otherModesAndExistingUsersNeedNothing() {
        when(users.count()).thenReturn(1L);

        bootstrap(SecurityMode.FORM, "", "").run(null);
        bootstrap(SecurityMode.NONE, "", "").run(null);
        verifyNoInteractions(userService);
    }

    @Test
    void adminFormModeNeedsAdministratorSettingsAndRefusesAProxyAccountAsAdministrator() {
        assertThatThrownBy(() -> bootstrap(SecurityMode.PROXY_HEADER_ADMIN_FORM, "", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY_ADMIN_PASSWORD");

        when(users.findByUsername("erika")).thenReturn(Optional.of(new UserRepository.Row("1", "erika", null, null,
                false, true, null)));

        assertThatThrownBy(() -> bootstrap(SecurityMode.PROXY_HEADER_ADMIN_FORM, "Erika", "a long password!").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a password");
    }

    @Test
    void adminFormModeCreatesTheAdministratorEvenWhenProxyUsersExist() {
        when(users.count()).thenReturn(3L);
        when(users.findByUsername("admin")).thenReturn(Optional.empty());

        bootstrap(SecurityMode.PROXY_HEADER_ADMIN_FORM, "admin", "a long password!").run(null);

        verify(userService).create(new UserInput("admin", null, "a long password!", true));
    }
}
