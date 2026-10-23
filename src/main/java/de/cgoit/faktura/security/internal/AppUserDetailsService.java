package de.cgoit.faktura.security.internal;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a user for the sign-in form. A user without password (created behind a proxy), an inactive user and a locked
 * one cannot sign in; the form shows the same message for all of them.
 */
@Service
class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final LoginThrottle throttle;

    AppUserDetailsService(UserRepository users, LoginThrottle throttle) {
        this.users = users;
        this.throttle = throttle;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var row = users.findByUsername(Usernames.normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        var roles = row.admin() ? new String[] { "USER", "ADMIN" } : new String[] { "USER" };

        return User.withUsername(row.username())
                .password(row.passwordHash() == null ? "" : row.passwordHash())
                .roles(roles)
                .disabled(!row.active() || row.passwordHash() == null)
                .accountLocked(throttle.isLocked(row.username()))
                .build();
    }
}
