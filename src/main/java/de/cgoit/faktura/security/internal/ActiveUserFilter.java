package de.cgoit.faktura.security.internal;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends the session of a signed-in user as soon as the user was deactivated or lost or gained the administrator right,
 * so a change in the user administration takes effect at the next request and not when the session expires.
 */
class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository users;

    ActiveUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            var admin = authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
            var current = users.findByUsername(Usernames.normalize(authentication.getName()));
            var unchanged = current.isPresent()
                    && current.get().active()
                    && current.get().admin() == admin;

            if (!unchanged) {
                var session = request.getSession(false);

                if (session != null) {
                    session.invalidate();
                }

                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
