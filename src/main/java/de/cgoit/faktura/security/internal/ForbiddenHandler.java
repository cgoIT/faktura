package de.cgoit.faktura.security.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Answers a denied request (missing CSRF token, missing right) with status 403. A htmx request gets only the status,
 * the page of the application shows its own message; everything else gets the error page {@code error/403}.
 */
class ForbiddenHandler implements AccessDeniedHandler {

    private final boolean redirectAdmins;

    /**
     * @param redirectAdmins {@code true} in mode {@code PROXY_HEADER_ADMIN_FORM}: an administrator in admin mode who
     *                       opens a page outside the user administration is sent there instead of seeing an error
     */
    ForbiddenHandler(boolean redirectAdmins) {
        this.redirectAdmins = redirectAdmins;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (request.getHeader("HX-Request") != null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());

            return;
        }

        if (redirectAdmins && !(exception instanceof CsrfException) && "GET".equals(request.getMethod())
                && isAdmin()) {
            response.sendRedirect(request.getContextPath() + "/settings/users");

            return;
        }

        response.sendError(HttpStatus.FORBIDDEN.value());
    }

    private static boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
