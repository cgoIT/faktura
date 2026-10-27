package de.cgoit.faktura.mcp.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.List;

import de.cgoit.faktura.mcp.McpScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request through when it carries a valid token, as bearer token or in the configured header. A token is valid
 * when an administrator created it, it is not revoked and its last day has not passed; the actor is {@code mcp:<name>}
 * and the scope of the token becomes the authority {@code SCOPE_READ} or {@code SCOPE_DRAFT}. The optional token of
 * the environment gets the actor {@value #EMERGENCY_ACTOR} and reading only. Tokens are compared by their hashes, so
 * the lookup does not depend on how much of a token is right.
 */
class BearerTokenFilter extends OncePerRequestFilter {

    static final String EMERGENCY_ACTOR = "mcp";
    static final String SCOPE_PREFIX = "SCOPE_";

    private static final String PREFIX = "Bearer ";

    private final byte[] emergency;
    private final String tokenHeader;
    private final McpTokenRepository tokens;
    private final Clock clock;

    BearerTokenFilter(String emergencyToken, String tokenHeader, McpTokenRepository tokens, Clock clock) {
        this.emergency = emergencyToken.isEmpty() ? null : TokenHashes.sha256(emergencyToken);
        this.tokenHeader = tokenHeader;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        var given = header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                ? header.substring(PREFIX.length())
                : request.getHeader(tokenHeader);

        if (given != null && !given.isBlank()) {
            authenticate(given.strip());
        }

        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        var hash = TokenHashes.sha256(token);

        if (emergency != null && MessageDigest.isEqual(emergency, hash)) {
            grant(EMERGENCY_ACTOR, McpScope.READ);

            return;
        }

        tokens.findValid(TokenHashes.sha256Hex(token), LocalDate.now(clock).toString())
                .ifPresent(row -> {
                    grant("mcp:" + row.name(), McpScope.valueOf(row.scope()));
                    markUsed(row.id());
                });
    }

    private static void grant(String actor, McpScope scope) {
        var context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(SCOPE_PREFIX + scope.name()))));
        SecurityContextHolder.setContext(context);
    }

    private void markUsed(String id) {
        var now = Instant.now(clock);

        try {
            tokens.touch(id, now.toString(), now.minus(1, ChronoUnit.MINUTES).toString());
        } catch (DataAccessException e) {
            // the time of the last use is a convenience; a busy database must not turn a valid call into an error
            logger.debug("Could not record the use of a token");
        }
    }
}
