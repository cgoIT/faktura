package de.cgoit.faktura.security.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.cgoit.faktura.security.UserInfo;
import de.cgoit.faktura.security.UserService;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the username from a header of the reverse proxy in mode {@code PROXY_HEADER}. The header counts only if the
 * request comes from a trusted address; anything else is refused, never signed in as someone. The health check of the
 * container passes without a header.
 */
class ProxyHeaderFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyHeaderFilter.class);
    private static final int MAX_NAME_LENGTH = 100;
    private static final long LOG_INTERVAL_MILLIS = 60_000;

    private final String header;
    private final String nameHeader;
    private final List<IpAddressMatcher> trusted;
    private final UserService users;
    private final boolean adminByFormOnly;
    private final String adminUser;
    private volatile String lastKey = "";
    private volatile long lastLoggedAt;

    ProxyHeaderFilter(SecurityProperties properties, UserService users, boolean adminByFormOnly) {
        this.header = properties.header();
        this.nameHeader = properties.nameHeader();
        this.trusted = properties.trustedProxies().stream()
                .map(String::strip)
                .map(IpAddressMatcher::new)
                .toList();
        this.users = users;
        this.adminByFormOnly = adminByFormOnly;
        this.adminUser = properties.adminUser() == null || properties.adminUser().isBlank()
                ? null
                : Usernames.normalize(properties.adminUser());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator/health")) {
            chain.doFilter(request, response);

            return;
        }

        var name = request.getHeader(header);

        if (!isTrusted(peerAddress(request))) {
            refuse(request, response, "address not in trusted-proxies");

            return;
        }

        if (adminByFormOnly && hasSignedInUser()) {
            chain.doFilter(request, response);

            return;
        }

        if (name == null || name.isBlank()) {
            refuse(request, response, "header " + header + " missing or empty");

            return;
        }

        if (!isUsable(name)) {
            refuse(request, response, "header " + header + " too long");

            return;
        }

        if (adminByFormOnly && Usernames.normalize(name).equals(adminUser)) {
            refuse(request, response, "header names the administrator, who signs in with a password");

            return;
        }

        UserInfo user;

        try {
            user = users.provision(name, displayName(request));
        } catch (RuleViolationException e) {
            refuse(request, response, "header " + header + " holds characters a user name may not have");

            return;
        }

        if (!user.active()) {
            refuse(request, response, "user is deactivated");

            return;
        }

        if (adminByFormOnly && (user.passwordSet() || user.username().equals(adminUser))) {
            refuse(request, response, "header names an account that signs in with a password");

            return;
        }

        var authorities = user.admin() && !adminByFormOnly
                ? AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")
                : AuthorityUtils.createAuthorityList("ROLE_USER");
        var context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.username(), null,
                authorities));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    /**
     * Reads the display name from the configured header. Tomcat decodes header bytes as ISO-8859-1, but proxies send
     * UTF-8; a value that is valid UTF-8 when read back is turned into the intended text.
     */
    private String displayName(HttpServletRequest request) {
        if (nameHeader == null || nameHeader.isBlank()) {
            return null;
        }

        var value = request.getHeader(nameHeader);

        if (value == null) {
            return null;
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1)))
                    .toString();
        } catch (CharacterCodingException e) {
            return value;
        }
    }

    /**
     * Tells whether a sign-in from the form (a session) exists; in the combined mode it wins over the header.
     */
    private static boolean hasSignedInUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Answers 403 and logs why, with the address of the connection but never a header value. The same reason from the
     * same address is logged once a minute, so the many requests of one page do not flood the log.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        var key = reason + "|" + peerAddress(request);
        var now = System.currentTimeMillis();

        if (!key.equals(lastKey) || now - lastLoggedAt > LOG_INTERVAL_MILLIS) {
            lastKey = key;
            lastLoggedAt = now;
            LOG.warn("Request refused in mode PROXY_HEADER: {} (peer address {})", reason, peerAddress(request));
        }

        response.sendError(HttpStatus.FORBIDDEN.value());
    }

    private static boolean isUsable(String name) {
        return name.length() <= MAX_NAME_LENGTH;
    }

    /**
     * Address of the connection itself. The {@code ForwardedHeaderFilter} (strategy {@code framework}) replaces
     * {@code getRemoteAddr()} with the client address from {@code X-Forwarded-For}, which anybody can send; the trust
     * decision must not depend on it, so the wrappers are removed to reach the request of the container.
     */
    private static String peerAddress(HttpServletRequest request) {
        ServletRequest current = request;

        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }

        return current.getRemoteAddr();
    }

    private boolean isTrusted(String address) {
        return address != null && trusted.stream()
                .anyMatch(matcher -> matcher.matches(address));
    }
}
