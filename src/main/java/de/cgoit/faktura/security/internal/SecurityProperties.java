package de.cgoit.faktura.security.internal;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the security module ({@code faktura.security.*} in application.yml).
 *
 * @param mode how people are identified
 * @param header header with the username in mode {@code PROXY_HEADER}
 * @param nameHeader header with the display name in mode {@code PROXY_HEADER}, empty for none
 * @param trustedProxies addresses or CIDR ranges that may set the headers
 * @param csp value of the Content-Security-Policy header
 * @param minPasswordLength shortest password a user can set
 * @param maxFailures failed sign-ins in a row before the account is locked for a while
 * @param lockDuration how long the lock lasts
 * @param proxyLoginInterval how long a visit through the proxy counts as the same sign-in for the time of the last login
 * @param adminUser username of the administrator created at first start (or marked as administrator in mode
 *        {@code PROXY_HEADER}); the password is only used when no user exists
 * @param adminPassword password of that administrator
 */
@ConfigurationProperties("faktura.security")
public record SecurityProperties(
        @DefaultValue("NONE") SecurityMode mode,
        @DefaultValue("X-Forwarded-User") String header,
        @DefaultValue("") String nameHeader,
        @DefaultValue({ "127.0.0.1/32", "::1/128" }) List<String> trustedProxies,
        @DefaultValue("default-src 'self'") String csp,
        @DefaultValue("12") int minPasswordLength,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("30m") Duration proxyLoginInterval,
        String adminUser,
        String adminPassword) {

    /**
     * Hides the initial password from logs.
     */
    @Override
    public String toString() {
        return "SecurityProperties[mode=" + mode + "]";
    }
}
