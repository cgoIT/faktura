package de.cgoit.faktura.mail.web;

import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailTemplateInput;
import de.cgoit.faktura.shared.MailTone;

/**
 * Form data of a mail template; rules are checked by the service.
 */
public record MailTemplateForm(MailScope scope, String name, String subject, String body, Boolean makeDefault,
        MailTone tone) {

    static MailTemplateForm empty(MailScope scope) {
        return new MailTemplateForm(scope, "", "", "", Boolean.FALSE, null);
    }

    MailTemplateInput toInput() {
        return new MailTemplateInput(scope, name, subject, body, Boolean.TRUE.equals(makeDefault), tone);
    }
}
