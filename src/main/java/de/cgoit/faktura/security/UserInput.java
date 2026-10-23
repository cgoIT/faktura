package de.cgoit.faktura.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Values to create a user.
 *
 * @param username sign-in name: letters, digits and {@code . _ @ + -}; stored in lower case
 * @param password initial password; its minimum length is a setting, so the service checks it
 */
public record UserInput(
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}")
        @Pattern(regexp = "[A-Za-z0-9._@+-]*", message = "{user.error.usernameFormat}") String username,
        @Size(max = 200, message = "{validation.tooLong}") String displayName,
        String password,
        boolean admin) {

    /**
     * Hides the password from logs.
     */
    @Override
    public String toString() {
        return "UserInput[]";
    }
}
