package de.cgoit.faktura.security.web;

import de.cgoit.faktura.security.internal.SecurityMode;
import de.cgoit.faktura.security.internal.SecurityProperties;
import de.cgoit.faktura.shared.CurrentUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every page who is signed in and in which mode the application runs, for the user area of the layout and the
 * "Users" tab.
 */
@ControllerAdvice
public class SecurityAdvice {

    private final SecurityProperties properties;
    private final CurrentUser currentUser;

    public SecurityAdvice(SecurityProperties properties, CurrentUser currentUser) {
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @ModelAttribute("securityMode")
    public String securityMode() {
        return properties.mode().name();
    }

    @ModelAttribute("currentUser")
    public String currentUser() {
        return currentUser.username();
    }

    /**
     * Tells whether the page belongs to an administrator in admin mode of the combined mode: the menu then shows only
     * the user administration.
     */
    @ModelAttribute("adminOnly")
    public boolean adminOnly() {
        return properties.mode() == SecurityMode.PROXY_HEADER_ADMIN_FORM && currentUserAdmin();
    }

    @ModelAttribute("currentUserAdmin")
    public boolean currentUserAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
