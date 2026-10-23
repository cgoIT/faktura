package de.cgoit.faktura.shared;

import org.springframework.stereotype.Component;

/**
 * Fallback actor when no sign-in module is active: every action belongs to the system.
 */
@Component
class SystemCurrentUser implements CurrentUser {

    @Override
    public String username() {
        return SYSTEM;
    }
}
