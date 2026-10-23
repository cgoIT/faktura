package de.cgoit.faktura.security.internal;

import de.cgoit.faktura.security.UserService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Security filter chain. CSRF protection and security headers apply in every mode; the mode decides who is let in.
 * Pages may only be framed by the application itself (policy directive {@code frame-ancestors}).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

    private static final String[] PUBLIC = { "/css/**", "/js/**", "/fonts/**", "/images/**", "/login", "/error",
            "/actuator/health", "/actuator/health/**" };

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties,
            UserDetailsService userDetails, PasswordEncoder encoder, UserService users, UserRepository repository)
            throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(properties.csp()))
                        // the policy directive frame-ancestors does the job of X-Frame-Options
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)))
                .exceptionHandling(handling -> handling.accessDeniedHandler(new ForbiddenHandler(properties.mode() == SecurityMode.PROXY_HEADER_ADMIN_FORM)));

        switch (properties.mode()) {
            case NONE -> http
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
            case FORM -> {
                var provider = new DaoAuthenticationProvider(userDetails);

                provider.setPasswordEncoder(encoder);
                http.authenticationProvider(provider)
                        .addFilterBefore(new ActiveUserFilter(repository), AuthorizationFilter.class)
                        .authorizeHttpRequests(requests -> requests
                                .requestMatchers(PUBLIC).permitAll()
                                .requestMatchers("/settings/users/**").hasRole("ADMIN")
                                .anyRequest().authenticated())
                        .formLogin(form -> form.loginPage("/login").failureUrl("/login?error").permitAll())
                        .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
            }
            case PROXY_HEADER_ADMIN_FORM -> {
                var provider = new DaoAuthenticationProvider(userDetails);

                provider.setPasswordEncoder(encoder);
                // the header filter runs before the sign-in filter so that the trusted-address rule covers the form too
                http.authenticationProvider(provider)
                        .addFilterBefore(new ProxyHeaderFilter(properties, users, true),
                                UsernamePasswordAuthenticationFilter.class)
                        .addFilterBefore(new ActiveUserFilter(repository), ProxyHeaderFilter.class)
                        .exceptionHandling(handling -> handling
                                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
                        // admin mode is for the user administration only; the daily work is done as a proxy user
                        .authorizeHttpRequests(requests -> requests
                                .requestMatchers(PUBLIC).permitAll()
                                .requestMatchers("/settings/users/**", "/account/**").hasRole("ADMIN")
                                .anyRequest().access((authentication, context) -> new AuthorizationDecision(
                                        !hasAdminRole(authentication.get()))))
                        .formLogin(form -> form.loginPage("/login").failureUrl("/login?error")
                                .defaultSuccessUrl("/settings/users", true).permitAll())
                        .logout(logout -> logout.logoutSuccessUrl("/"));
            }
            case PROXY_HEADER -> http
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(new ProxyHeaderFilter(properties, users, false), AnonymousAuthenticationFilter.class)
                    .exceptionHandling(handling -> handling
                            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
                    .authorizeHttpRequests(requests -> requests
                            .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                            .requestMatchers("/settings/users/**").hasRole("ADMIN")
                            .anyRequest().authenticated());
        }

        return http.build();
    }

    private static boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
