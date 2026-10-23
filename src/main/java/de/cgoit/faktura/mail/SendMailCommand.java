package de.cgoit.faktura.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the user confirmed in the send dialog.
 *
 * @param recipients addresses separated by comma or semicolon
 * @param body       text with markup; sent as it stands, placeholders are not replaced again
 * @param confirmed  the user checked the mail and asked to send it; without it nothing is sent
 */
public record SendMailCommand(
        @NotNull(message = "{validation.required}") MailTarget target,
        @NotBlank(message = "{validation.required}") @Size(max = 500, message = "{validation.tooLong}") String recipients,
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}")
        @Pattern(regexp = "[^\\r\\n]*", message = "{mail.error.subjectLine}") String subject,
        @NotBlank(message = "{validation.required}") @Size(max = 10000, message = "{validation.tooLong}") String body,
        boolean confirmed) {

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "SendMailCommand[confirmed=" + confirmed + "]";
    }
}
