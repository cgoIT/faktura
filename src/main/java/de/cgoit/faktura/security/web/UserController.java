package de.cgoit.faktura.security.web;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import de.cgoit.faktura.security.UserInfo;
import de.cgoit.faktura.security.UserInput;
import de.cgoit.faktura.security.UserService;
import de.cgoit.faktura.security.internal.SecurityMode;
import de.cgoit.faktura.security.internal.SecurityProperties;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * User administration, for administrators only (the filter chain checks the right). Without sign-in there are no
 * users to manage. Behind a proxy the users appear by themselves and have no password.
 */
@Controller
@RequestMapping("/settings/users")
public class UserController {

    private static final DateTimeFormatter LAST_LOGIN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final UserService users;
    private final SecurityProperties properties;
    private final Clock clock;

    public UserController(UserService users, SecurityProperties properties, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A user as the list shows it.
     *
     * @param lastLogin  time of the last sign-in in local time, {@code null} if there was none
     * @param lastAdmin  the only active administrator: neither the right nor the account may be taken away
     */
    public record Row(UserInfo user, String lastLogin, boolean lastAdmin) {
    }

    @GetMapping
    public String list(Model model) {
        requireUsers();
        var all = users.list();
        var activeAdmins = all.stream()
                .filter(user -> user.admin() && user.active())
                .count();

        model.addAttribute("users", all.stream()
                .map(user -> new Row(user, formatLastLogin(user), user.admin() && user.active() && activeAdmins == 1))
                .toList());
        model.addAttribute("passwords", properties.mode().usesForm());
        model.addAttribute("minPasswordLength", properties.minPasswordLength());

        return "security/users";
    }

    @GetMapping("/new")
    public String form(Model model) {
        requirePasswords();
        model.addAttribute("minPasswordLength", properties.minPasswordLength());

        return "security/user-form";
    }

    /**
     * Creates a user. If the input is refused, the form opens again with the error and the values typed (not the
     * passwords).
     */
    @PostMapping
    public String create(@RequestParam String username, @RequestParam(defaultValue = "") String displayName,
            @RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String passwordRepeat,
            @RequestParam(defaultValue = "false") boolean admin, RedirectAttributes redirect) {
        requirePasswords();

        try {
            requireSame(password, passwordRepeat);
            users.create(new UserInput(username, displayName, password, admin));
            redirect.addFlashAttribute("message", "user.created");

            return "redirect:/settings/users";
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
            redirect.addFlashAttribute("username", username);
            redirect.addFlashAttribute("displayName", displayName);
            redirect.addFlashAttribute("admin", admin);

            return "redirect:/settings/users/new";
        }
    }

    @PostMapping("/{id}/password")
    public String setPassword(@PathVariable String id, @RequestParam String password,
            @RequestParam String passwordRepeat, RedirectAttributes redirect) {
        requirePasswords();

        return handle(redirect, "user.passwordChanged", () -> {
            requireSame(password, passwordRepeat);
            users.setPassword(id, password);
        });
    }

    @PostMapping("/{id}/admin")
    public String setAdmin(@PathVariable String id, @RequestParam boolean admin, RedirectAttributes redirect) {
        requireUsers();

        return handle(redirect, "user.saved", () -> users.setAdmin(id, admin));
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable String id, @RequestParam boolean active, RedirectAttributes redirect) {
        requireUsers();

        return handle(redirect, "user.saved", () -> users.setActive(id, active));
    }

    private String handle(RedirectAttributes redirect, String successKey, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("message", successKey);
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/settings/users";
    }

    private String formatLastLogin(UserInfo user) {
        if (user.lastLogin() == null) {
            return null;
        }

        return LAST_LOGIN.format(Instant.parse(user.lastLogin()).atZone(clock.getZone()));
    }

    private static void requireSame(String password, String repeat) {
        if (!password.equals(repeat)) {
            throw new RuleViolationException("user.error.passwordMismatch");
        }
    }

    private void requireUsers() {
        if (properties.mode() == SecurityMode.NONE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void requirePasswords() {
        if (!properties.mode().usesForm()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
