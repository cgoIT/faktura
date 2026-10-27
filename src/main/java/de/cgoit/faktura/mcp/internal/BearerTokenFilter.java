package de.cgoit.faktura.mcp.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request through as the actor {@value #ACTOR} when it carries the configured token as bearer token or in the header {@value #TOKEN_HEADER}. Both sides are
 * hashed before the comparison, so it takes the same time whatever the token looks like.
 */
class BearerTokenFilter extends OncePerRequestFilter {

    static final String ACTOR = "mcp";

    private static final String PREFIX = "Bearer ";

    // for clients that can only send a plain header value (the connector dialog of Claude Desktop)
    static final String TOKEN_HEADER = "X-MCP-Token";

    private final byte[] expected;

    BearerTokenFilter(String token) {
        this.expected = sha256(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        var given = header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                ? header.substring(PREFIX.length())
                : request.getHeader(TOKEN_HEADER);

        if (given != null && MessageDigest.isEqual(expected, sha256(given.strip()))) {
            var context = SecurityContextHolder.createEmptyContext();

            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR, null, List.of()));
            SecurityContextHolder.setContext(context);
        }

        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
