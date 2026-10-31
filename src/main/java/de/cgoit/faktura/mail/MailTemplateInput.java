package de.cgoit.faktura.mail;

import de.cgoit.faktura.shared.MailTone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Editable values of a mail template. The annotations are the rules for the values; the service checks them.
 *
 * @param makeDefault make this the default template of its scope
 * @param tone        customers with this mail tone get this template, {@code null} for none
 */
public record MailTemplateInput(
        @NotNull(message = "{validation.required}") MailScope scope,
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String name,
        @NotBlank(message = "{validation.required}") @Size(max = 200, message = "{validation.tooLong}")
        @Pattern(regexp = "[^\\r\\n]*", message = "{mail.error.subjectLine}") String subject,
        @NotBlank(message = "{validation.required}") @Size(max = 4000, message = "{validation.tooLong}") String body,
        boolean makeDefault,
        MailTone tone) {

    /**
     * Creates input without a tone.
     */
    public MailTemplateInput(MailScope scope, String name, String subject, String body, boolean makeDefault) {
        this(scope, name, subject, body, makeDefault, null);
    }
}
