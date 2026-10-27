package de.cgoit.faktura.mcp.internal;

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
 * bearer token lets a request in. The chain of the web interface is not touched. Without a usable token the
 * application does not start.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
class McpSecurityConfiguration {

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "faktura.mcp.enabled", havingValue = "true")
    SecurityFilterChain mcpFilterChain(HttpSecurity http, McpProperties properties) throws Exception {
        if (properties.token().strip().length() < McpProperties.MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("faktura.mcp.token must have at least "
                    + McpProperties.MIN_TOKEN_LENGTH + " characters");
        }

        http.securityMatcher("/mcp")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new BearerTokenFilter(properties.token().strip(), properties.tokenHeader().strip()), AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            response.sendError(HttpStatus.UNAUTHORIZED.value());
                        }))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        return http.build();
    }
}
