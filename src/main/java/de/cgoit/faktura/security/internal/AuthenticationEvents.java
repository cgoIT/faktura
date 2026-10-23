package de.cgoit.faktura.security.internal;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins for the lock and records the time of a successful one.
 */
@Component
class AuthenticationEvents {

    private final LoginThrottle throttle;
    private final UserRepository users;
    private final Clock clock;

    AuthenticationEvents(LoginThrottle throttle, UserRepository users, Clock clock) {
        this.throttle = throttle;
        this.users = users;
        this.clock = clock;
    }

    @EventListener
    void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        var username = Usernames.normalize(event.getAuthentication().getName());

        // only names that exist are counted: random names must neither fill the table nor push real counters out
        if (users.findByUsername(username).isPresent()) {
            throttle.fail(username);
        }
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        var username = Usernames.normalize(event.getAuthentication().getName());

        throttle.reset(username);
        users.updateLastLogin(username, Instant.now(clock).toString());
    }
}
