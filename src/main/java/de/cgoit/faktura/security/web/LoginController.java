package de.cgoit.faktura.security.web;

import de.cgoit.faktura.security.internal.SecurityProperties;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page. Spring Security handles the form post; without a sign-in form (other modes) the page is not there.
 */
@Controller
public class LoginController {

    private final SecurityProperties properties;

    public LoginController(SecurityProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/login")
    public String login() {
        return properties.mode().usesForm() ? "security/login" : "redirect:/";
    }
}
