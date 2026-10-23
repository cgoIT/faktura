package de.cgoit.faktura.security.web;

import de.cgoit.faktura.security.UserService;
import de.cgoit.faktura.security.internal.SecurityProperties;
import de.cgoit.faktura.shared.CurrentUser;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The signed-in user changes their own password (mode {@code FORM} only).
 */
@Controller
@RequestMapping("/account/password")
public class AccountController {

    private final UserService users;
    private final CurrentUser currentUser;
    private final SecurityProperties properties;

    public AccountController(UserService users, CurrentUser currentUser, SecurityProperties properties) {
        this.users = users;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @GetMapping
    public String form(Model model) {
        requireForm();
        model.addAttribute("minPasswordLength", properties.minPasswordLength());

        return "security/password";
    }

    @PostMapping
    public String change(@RequestParam String currentPassword, @RequestParam String newPassword,
            @RequestParam String newPasswordRepeat, RedirectAttributes redirect) {
        requireForm();

        try {
            if (!newPassword.equals(newPasswordRepeat)) {
                throw new RuleViolationException("user.error.passwordMismatch");
            }

            users.changeOwnPassword(currentUser.username(), currentPassword, newPassword);
            redirect.addFlashAttribute("message", "account.passwordChanged");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/account/password";
    }

    private void requireForm() {
        if (!properties.mode().usesForm()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
