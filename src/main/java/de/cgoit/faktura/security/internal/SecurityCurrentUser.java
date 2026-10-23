package de.cgoit.faktura.security.internal;

import de.cgoit.faktura.shared.CurrentUser;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The user of the current request: the authenticated username, {@code anonymous} for a request without sign-in, and
 * {@link CurrentUser#SYSTEM} outside a request (jobs, seeder).
 */
@Component
@Primary
class SecurityCurrentUser implements CurrentUser {

    static final String ANONYMOUS = "anonymous";

    @Override
    public String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            return SYSTEM;
        }

        return authentication instanceof AnonymousAuthenticationToken ? ANONYMOUS : authentication.getName();
    }
}
