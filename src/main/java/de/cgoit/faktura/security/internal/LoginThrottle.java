package de.cgoit.faktura.security.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Locks a username for a while after too many failed sign-ins in a row. Only names of existing users are counted, so
 * the table never grows beyond the number of users. The lock hits the name, not the address (behind the proxy all
 * requests come from one address): anyone who knows a name can lock that account for {@code lock-duration}.
 * The state lives in memory: the application runs as one instance, and a restart clears it.
 */
@Component
class LoginThrottle {

    private record State(int failures, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final SecurityProperties properties;
    private final Clock clock;

    LoginThrottle(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    boolean isLocked(String username) {
        var state = states.get(Usernames.normalize(username));

        return state != null && isActive(state, Instant.now(clock));
    }

    void fail(String username) {
        var now = Instant.now(clock);

        states.compute(Usernames.normalize(username), (name, state) -> {
            var failures = state == null || isExpired(state, now) ? 1 : state.failures() + 1;

            return failures >= properties.maxFailures()
                    ? new State(0, now.plus(properties.lockDuration()))
                    : new State(failures, null);
        });
    }

    void reset(String username) {
        states.remove(Usernames.normalize(username));
    }

    private static boolean isActive(State state, Instant now) {
        return state.lockedUntil() != null && state.lockedUntil().isAfter(now);
    }

    private static boolean isExpired(State state, Instant now) {
        return state.lockedUntil() != null && !state.lockedUntil().isAfter(now);
    }
}
