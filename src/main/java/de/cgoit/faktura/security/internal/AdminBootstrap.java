package de.cgoit.faktura.security.internal;

import static de.cgoit.faktura.shared.Texts.isBlank;

import de.cgoit.faktura.security.UserInput;
import de.cgoit.faktura.security.UserService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * In mode {@code FORM} the first start creates the administrator from {@code faktura.security.admin-user} and
 * {@code admin-password}. Without them, and without any user, nobody could sign in, so the start fails with a hint.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties properties;
    private final UserRepository users;
    private final UserService userService;

    AdminBootstrap(SecurityProperties properties, UserRepository users, UserService userService) {
        this.properties = properties;
        this.users = users;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.mode() == SecurityMode.PROXY_HEADER_ADMIN_FORM) {
            ensureFormAdministrator();

            return;
        }

        if (properties.mode() != SecurityMode.FORM || users.count() > 0) {
            return;
        }

        if (isBlank(properties.adminUser()) || isBlank(properties.adminPassword())) {
            throw new IllegalStateException("No user exists. Set SECURITY_ADMIN_USER and SECURITY_ADMIN_PASSWORD "
                    + "for the first start in mode FORM.");
        }

        try {
            userService.create(new UserInput(properties.adminUser(), null, properties.adminPassword(), true));
        } catch (RuleViolationException e) {
            throw new IllegalStateException("The initial administrator is invalid: " + e.messageKey()
                    + " (check SECURITY_ADMIN_USER and SECURITY_ADMIN_PASSWORD)", e);
        }

        LOG.info("Created the initial administrator");
    }

    /**
     * In mode {@code PROXY_HEADER_ADMIN_FORM} the administrator signs in with a password and must exist next to the
     * users the proxy creates, so it is created whenever it is missing. A name that already belongs to an account
     * without password (a proxy user) cannot be the administrator.
     */
    private void ensureFormAdministrator() {
        if (isBlank(properties.adminUser()) || isBlank(properties.adminPassword())) {
            throw new IllegalStateException("Mode PROXY_HEADER_ADMIN_FORM needs SECURITY_ADMIN_USER and "
                    + "SECURITY_ADMIN_PASSWORD for the administrator who signs in with a password.");
        }

        var existing = users.findByUsername(Usernames.normalize(properties.adminUser()));

        if (existing.isPresent()) {
            if (existing.get().passwordHash() == null) {
                throw new IllegalStateException("SECURITY_ADMIN_USER names an account that exists without a password "
                        + "(created through the proxy header). Choose another name for the administrator.");
            }

            return;
        }

        try {
            userService.create(new UserInput(properties.adminUser(), null, properties.adminPassword(), true));
        } catch (RuleViolationException e) {
            throw new IllegalStateException("The administrator is invalid: " + e.messageKey()
                    + " (check SECURITY_ADMIN_USER and SECURITY_ADMIN_PASSWORD)", e);
        }

        LOG.info("Created the administrator for mode PROXY_HEADER_ADMIN_FORM");
    }
}
