package de.cgoit.faktura.security;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import de.cgoit.faktura.security.internal.SecurityProperties;
import de.cgoit.faktura.security.internal.UserRepository;
import de.cgoit.faktura.security.internal.Usernames;
import de.cgoit.faktura.security.internal.UserRepository.Row;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users of the application: who may sign in, who is an administrator, and the passwords. There is no role model: every
 * active user may do everything except manage users, which only an administrator may. Users are never deleted, only
 * deactivated, because the audit log and the drafts name them. The last active administrator can neither be
 * deactivated nor demoted, so the administration cannot lock itself out.
 */
@Service
public class UserService {

    private static final int MAX_DISPLAY_NAME = 200;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final InputValidator validator;
    private final SecurityProperties properties;
    private final Clock clock;

    public UserService(UserRepository users, PasswordEncoder encoder, InputValidator validator,
            SecurityProperties properties, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserInfo> list() {
        return users.findAll().stream()
                .map(UserService::toInfo)
                .toList();
    }

    /**
     * Creates a user who can sign in with a password.
     *
     * @throws RuleViolationException if the input is invalid, the name is taken, or the password is too short
     */
    @Transactional
    public UserInfo create(UserInput input) {
        validator.check(input);

        var username = Usernames.normalize(input.username());

        requirePassword(input.password());

        var id = UUID.randomUUID().toString();
        var inserted = users.insert(id, username, blankToNull(input.displayName()), encoder.encode(input.password()),
                input.admin(), Instant.now(clock).toString());

        if (!inserted) {
            throw new RuleViolationException("user.error.exists", "username");
        }

        return toInfo(requireRow(id));
    }

    /**
     * Sets a new password, whatever the old one was (an administrator resets it).
     *
     * @throws RuleViolationException if the user is unknown or the password is too short
     */
    @Transactional
    public void setPassword(String id, String password) {
        var row = requireRow(id);

        requirePassword(password);
        users.updatePassword(row.id(), encoder.encode(password));
    }

    /**
     * Changes the password of the signed-in user after checking the current one.
     *
     * @throws RuleViolationException if the current password is wrong or the new one is too short
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        var row = users.findByUsername(username)
                .orElseThrow(() -> new RuleViolationException("user.error.unknown"));

        if (row.passwordHash() == null || !encoder.matches(currentPassword, row.passwordHash())) {
            throw new RuleViolationException("user.error.wrongPassword", "currentPassword");
        }

        requirePassword(newPassword);
        users.updatePassword(row.id(), encoder.encode(newPassword));
    }

    /**
     * Gives or takes the administrator right.
     *
     * @throws RuleViolationException if this would leave no active administrator
     */
    @Transactional
    public void setAdmin(String id, boolean admin) {
        var row = requireRow(id);

        if (!users.updateAdmin(row.id(), admin)) {
            throw new RuleViolationException("user.error.lastAdmin");
        }
    }

    /**
     * Activates or deactivates a user.
     *
     * @throws RuleViolationException if this would leave no active administrator
     */
    @Transactional
    public void setActive(String id, boolean active) {
        var row = requireRow(id);

        if (!users.updateActive(row.id(), active)) {
            throw new RuleViolationException("user.error.lastAdmin");
        }
    }

    /**
     * Returns the user for a name a trusted proxy reported, creating it on first sight (without password). The user
     * named in {@code faktura.security.admin-user} becomes an administrator when created; later changes of the right
     * are the administrators' to make.
     *
     * @throws RuleViolationException if the name contains characters a user name may not have
     */
    @Transactional
    public UserInfo provision(String reportedName) {
        return provision(reportedName, null);
    }

    /**
     * Like {@link #provision(String)}, and takes over the display name the proxy reported: it is stored for a new user
     * and replaces the stored one when the identity provider reports another. A blank name changes nothing, a name
     * over 200 characters is cut.
     *
     * @throws RuleViolationException if the name contains characters a user name may not have
     */
    @Transactional
    public UserInfo provision(String reportedName, String reportedDisplayName) {
        var username = Usernames.normalize(reportedName);
        var displayName = cleanedDisplayName(reportedDisplayName);
        var existing = users.findByUsername(username);

        if (existing.isPresent()) {
            var row = existing.get();

            var changed = false;

            if (displayName != null && !displayName.equals(row.displayName())) {
                users.updateDisplayName(row.id(), displayName);
                changed = true;
            }

            if (row.active() && isNewVisit(row.lastLogin())) {
                users.updateLastLogin(username, Instant.now(clock).toString());
                changed = true;
            }

            return changed
                    ? toInfo(users.findByUsername(username).orElseThrow())
                    : toInfo(row);
        }

        validator.check(new UserInput(username, displayName, null, false));

        var configured = properties.adminUser();
        var admin = configured != null && username.equals(Usernames.normalize(configured));

        users.insert(UUID.randomUUID().toString(), username, displayName, null, admin, Instant.now(clock).toString());
        users.updateLastLogin(username, Instant.now(clock).toString());

        return users.findByUsername(username)
                .map(UserService::toInfo)
                .orElseThrow();
    }

    /**
     * A visit through the proxy has no sign-in event; it counts as a new sign-in when the last one is older than
     * {@code proxy-login-interval}, so a busy session does not write the user row with every request.
     */
    private boolean isNewVisit(String lastLogin) {
        return lastLogin == null
                || Instant.parse(lastLogin).plus(properties.proxyLoginInterval()).isBefore(Instant.now(clock));
    }

    private static String cleanedDisplayName(String name) {
        if (name == null) {
            return null;
        }

        var cleaned = name.replaceAll("\\p{Cntrl}", " ")
                .strip();

        if (cleaned.isEmpty()) {
            return null;
        }

        return cleaned.length() > MAX_DISPLAY_NAME ? cleaned.substring(0, MAX_DISPLAY_NAME) : cleaned;
    }

    private void requirePassword(String password) {
        if (password == null || password.length() < properties.minPasswordLength()) {
            throw new RuleViolationException("user.error.passwordTooShort", "password");
        }
    }

    private Row requireRow(String id) {
        return users.findById(id)
                .orElseThrow(() -> new RuleViolationException("user.error.unknown"));
    }

    private static UserInfo toInfo(Row row) {
        return new UserInfo(row.id(), row.username(), row.displayName(), row.admin(), row.active(), row.lastLogin(),
                row.passwordHash() != null);
    }
}
