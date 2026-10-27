package de.cgoit.faktura.mcp.internal;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Separate filter chain for {@code /mcp}: stateless, no CSRF (there is no browser session to forge) and only the
 * a valid token lets a request in. The chain of the web interface is not touched. A token in the environment, if
 * set, must be long enough or the application does not start.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
class McpSecurityConfiguration {

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "faktura.mcp.enabled", havingValue = "true")
    SecurityFilterChain mcpFilterChain(HttpSecurity http, McpProperties properties, McpTokenRepository tokens,
            Clock clock) throws Exception {
        var emergency = properties.token().strip();

        if (!emergency.isEmpty() && emergency.length() < McpProperties.MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("faktura.mcp.token must be empty or have at least "
                    + McpProperties.MIN_TOKEN_LENGTH + " characters");
        }

        http.securityMatcher("/mcp")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new BearerTokenFilter(emergency, properties.tokenHeader().strip(), tokens, clock),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            response.sendError(HttpStatus.UNAUTHORIZED.value());
                        }))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        return http.build();
    }
}
