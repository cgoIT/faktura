package de.cgoit.faktura.mail.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the mail function ({@code faktura.mail.*}). The SMTP server itself is configured with
 * {@code spring.mail.*}; credentials only come from the environment.
 *
 * @param enabled            mail function switched on
 * @param from               sender address, blank: the e-mail address of the company
 * @param replyTo            optional reply address
 * @param bcc                optional blind copy of every mail
 * @param maxAttachmentBytes larger attachments are not sent
 * @param maxRecipients      most addresses in one mail
 */
@ConfigurationProperties("faktura.mail")
public record MailProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String from,
        @DefaultValue("") String replyTo,
        @DefaultValue("") String bcc,
        @DefaultValue("10485760") long maxAttachmentBytes,
        @DefaultValue("5") int maxRecipients) {
}
