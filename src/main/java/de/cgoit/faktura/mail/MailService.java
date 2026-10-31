package de.cgoit.faktura.mail;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchiveIntegrityException;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.archive.ArchivedFile;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.document.InvoiceLetterService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.MailBasis;
import de.cgoit.faktura.document.OfferEventType;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.mail.internal.MailAddresses;
import de.cgoit.faktura.mail.internal.MailLogRepository;
import de.cgoit.faktura.mail.internal.MailProperties;
import de.cgoit.faktura.mail.internal.MailRenderer;
import de.cgoit.faktura.mail.internal.SampleValues;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.MailTone;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextTemplateService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends finalized invoices, cancellations and dunning letters to the customer by mail. A mail is sent only when the
 * user asks for it and confirms it in the send dialog; nothing in the application sends on its own. The mail is
 * {@code multipart/mixed} with a {@code multipart/alternative} part (plain text first, then HTML) and the archived PDF
 * with unchanged bytes as attachment. Every attempt, successful or not, is logged insert-only and in the audit log.
 *
 * <p>Sending must never run inside a database transaction: the connection pool of SQLite has a single connection that
 * a slow SMTP server would block. The order is therefore read, build, send, and only then log in a short transaction.
 */
@Service
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);
    private static final int LOG_ATTEMPTS = 2;

    private final MailProperties properties;
    private final String smtpHost;
    private final ObjectProvider<JavaMailSender> senders;
    private final MailTemplateService templates;
    private final TextTemplateService texts;
    private final MailRenderer renderer;
    private final SampleValues samples;
    private final MailLogRepository log;
    private final InvoiceService invoices;
    private final OfferService offers;
    private final InvoiceLetterService letters;
    private final DunningService dunnings;
    private final CompanyService company;
    private final ArchiveService archive;
    private final AuditService audit;
    private final InputValidator validator;
    private final MessageSource messages;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MailService(MailProperties properties, @Value("${spring.mail.host:}") String smtpHost,
            ObjectProvider<JavaMailSender> senders, MailTemplateService templates, TextTemplateService texts,
            MailRenderer renderer, SampleValues samples, MailLogRepository log, InvoiceService invoices,
            OfferService offers, InvoiceLetterService letters, DunningService dunnings, CompanyService company,
            ArchiveService archive, AuditService audit, InputValidator validator, MessageSource messages,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.smtpHost = smtpHost;
        this.senders = senders;
        this.templates = templates;
        this.texts = texts;
        this.renderer = renderer;
        this.samples = samples;
        this.log = log;
        this.invoices = invoices;
        this.offers = offers;
        this.letters = letters;
        this.dunnings = dunnings;
        this.company = company;
        this.archive = archive;
        this.audit = audit;
        this.validator = validator;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Tells whether mail can be sent: the function must be switched on, an SMTP host and a sender address configured.
     */
    public MailStatus status() {
        var limit = properties.maxAttachmentBytes();

        if (!properties.enabled()) {
            return new MailStatus(false, "mail.disabled.off", limit);
        }

        if (smtpHost == null || smtpHost.isBlank() || senders.getIfAvailable() == null) {
            return new MailStatus(false, "mail.disabled.noHost", limit);
        }

        if (senderAddress().isEmpty()) {
            return new MailStatus(false, "mail.disabled.noSender", limit);
        }

        return new MailStatus(true, null, limit);
    }

    /**
     * Prepares the send dialog: recipient from the contact or the invoice address, text from the chosen template (or
     * the default of the document type) with the placeholders filled in, and the attachment. The draft says why a
     * mail cannot be sent ({@code blockedKey}) instead of failing.
     *
     * @param templateId template to start from, {@code null} or blank for the default
     * @throws RuleViolationException if the document is unknown or not finalized
     */
    public MailDraft prepare(MailTarget target, String templateId) {
        var source = resolve(target);
        var file = pdf(target);
        var template = chooseTemplate(source.scope(), templateId, source.basis().mailTone());
        var values = source.basis().placeholders();
        var subject = template.map(found -> renderer.subject(texts.expand(found.subject(), values))).orElse("");
        var body = template.map(found -> texts.expand(found.body(), values)).orElse("");
        var size = file.map(ArchivedFile::sizeBytes)
                .orElse(0L);
        var lastSent = history(target).stream()
                .filter(MailLogEntry::sent)
                .findFirst();

        return new MailDraft(target, source.scope(), source.basis().number(), prefilledRecipients(source.basis()),
                template, subject, body, attachmentName(source), size, blockedKey(file), lastSent);
    }

    /**
     * Picks the requested template if it belongs to the document type; without a request the template with the
     * tone of the customer, else the default of the type.
     */
    private Optional<MailTemplateInfo> chooseTemplate(MailScope scope, String templateId, MailTone tone) {
        if (templateId == null || templateId.isBlank()) {
            return templates.list(scope)
                    .stream()
                    .filter(found -> tone != null && found.tone() == tone)
                    .findFirst()
                    .or(() -> templates.defaultFor(scope));
        }

        return templates.find(templateId)
                .filter(found -> found.scope() == scope)
                .or(() -> templates.defaultFor(scope));
    }

    /**
     * Renders subject and text as the recipient will see them. Placeholders are not replaced: the text is final.
     */
    public MailPreview preview(String subject, String body) {
        var plainSubject = renderer.finalSubject(subject);

        return new MailPreview(plainSubject, renderer.html(plainSubject, body), renderer.text(body), Map.of());
    }

    /**
     * Renders a template for the template editor, with the data of a document or, without one, with invented sample
     * values. Nothing is saved.
     *
     * @param target document whose values to use, {@code null} for sample values
     * @throws RuleViolationException if subject or text use an unknown placeholder or the document is not finalized
     */
    public MailPreview previewTemplate(String subject, String body, MailTarget target) {
        var values = target == null ? samples.values() : resolve(target).basis().placeholders();
        var plainSubject = renderer.subject(texts.expand(subject, values));
        var text = texts.expand(body, values);

        return new MailPreview(plainSubject, renderer.html(plainSubject, text), renderer.text(text), values);
    }

    /**
     * Lists the send attempts for a document, newest first.
     */
    public List<MailLogEntry> history(MailTarget target) {
        return log.findByOwner(target.type(), target.id(), clock.getZone());
    }

    /**
     * Sends the mail. The attachment is the archived PDF of the document with unchanged bytes. A failed attempt is
     * logged as {@code FAILED} and reported; it is not repeated automatically.
     *
     * @throws IllegalStateException  if called inside a database transaction
     * @throws RuleViolationException if the user did not confirm, a value is invalid, mail is not available, the
     *                                document is not finalized, the attachment is missing or too large, or the SMTP
     *                                server did not accept the mail (the message key names the reason)
     */
    public MailLogEntry send(SendMailCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Mail must not be sent inside a database transaction");
        }

        validator.check(command);

        if (!command.confirmed()) {
            throw new RuleViolationException("mail.error.notConfirmed");
        }

        var status = status();

        if (!status.available()) {
            throw new RuleViolationException(status.reasonKey());
        }

        var recipients = MailAddresses.parse(command.recipients(), properties.maxRecipients());
        var source = resolve(command.target());
        var file = pdf(command.target()).orElseThrow(() -> new RuleViolationException("mail.error.noAttachment"));

        if (file.sizeBytes() > properties.maxAttachmentBytes()) {
            throw new RuleViolationException("mail.error.tooLarge");
        }

        var subject = renderer.finalSubject(command.subject());
        var attachmentName = attachmentName(source);
        var content = read(file);
        var sender = senders.getObject();
        var message = build(sender, recipients, subject, command.body(), attachmentName, content);
        String errorKey = null;
        String messageId = null;

        try {
            sender.send(message);
            messageId = message.getMessageID();
        } catch (MailException | MessagingException e) {
            errorKey = errorKey(e);
            LOG.warn("Sending a mail about {} {} failed: {} ({})", command.target().type(), command.target().id(),
                    errorKey, e.getClass().getSimpleName());
        }

        var entry = record(command, source, file, attachmentName, subject, recipients, messageId, errorKey);

        if (errorKey != null) {
            throw new RuleViolationException(errorKey);
        }

        if (source.scope() == MailScope.OFFER) {
            recordOfferSent(command.target().id());
        }

        return entry;
    }

    /**
     * Sends a short notice to the operator: one recipient, no attachment, no blind copy. It is not logged in
     * {@code mail_log} (which only knows documents); the caller keeps its own proof. A failure is reported as a
     * message key and never repeated here.
     *
     * @param recipient a single plain address
     * @param body      text with the markup of {@code RichText}
     * @throws IllegalStateException  if called inside a database transaction
     * @throws RuleViolationException if mail is not available, the address is invalid or the SMTP server did not
     *                                accept the mail (the message key names the reason)
     */
    public void sendNotice(String recipient, String subject, String body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Mail must not be sent inside a database transaction");
        }

        var status = status();

        if (!status.available()) {
            throw new RuleViolationException(status.reasonKey());
        }

        var addresses = MailAddresses.parse(recipient == null ? "" : recipient, 1);
        var plainSubject = renderer.finalSubject(subject);
        var sender = senders.getObject();

        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            var from = senderAddress().orElseThrow();

            helper.setFrom(new InternetAddress(from, company.find()
                    .map(CompanyInfo::name)
                    .orElse(null), "UTF-8"));
            helper.setTo(addresses.getFirst());
            helper.setSubject(plainSubject);
            helper.setText(renderer.text(body), renderer.html(plainSubject, body));
            sender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            var errorKey = errorKey(e);

            LOG.warn("Sending a notice failed: {} ({})", errorKey, e.getClass().getSimpleName());

            throw new RuleViolationException(errorKey);
        }
    }

    /**
     * Checks that the text is one plain e-mail address and returns it trimmed, for settings that name a recipient.
     *
     * @param field path of the input the violation is shown at
     * @throws RuleViolationException if the text is not exactly one plain address
     */
    public String normalizeAddress(String raw, String field) {
        try {
            return MailAddresses.parse(raw == null ? "" : raw, 1)
                    .getFirst()
                    .getAddress();
        } catch (RuleViolationException e) {
            throw new RuleViolationException("mail.error.recipients", field);
        }
    }

    private MailLogEntry record(SendMailCommand command, Source source, ArchivedFile file, String attachmentName,
            String subject, List<InternetAddress> recipients, String messageId, String errorKey) {
        var now = Instant.now(clock);
        var joined = recipients.stream()
                .map(InternetAddress::getAddress)
                .collect(Collectors.joining(", "));
        var row = new MailLogRepository.Row(UUID.randomUUID().toString(), command.target().type(),
                command.target().id(), joined, subject, command.body(), file.id(), attachmentName, errorKey == null,
                errorKey, messageId, now);

        writeLog(row, source, command.target());

        return new MailLogEntry(row.id(), row.ownerType(), row.ownerId(), row.recipients(), row.subject(), row.body(),
                row.attachmentName(), row.sent(), row.errorKey(), LocalDateTime.ofInstant(now, clock.getZone()));
    }

    /**
     * Writes the log row and the audit entry in one short transaction. The mail has already left (or failed), so a
     * failure is retried once; if it persists, the owner id and message id (no personal data) go to the error log
     * so that the send can still be reconstructed.
     */
    private void writeLog(MailLogRepository.Row row, Source source, MailTarget target) {
        for (var attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(txStatus -> {
                    log.insert(row);
                    audit.record(target.type() == MailOwnerType.DOCUMENT
                                    ? AuditObjectType.DOCUMENT
                                    : AuditObjectType.DUNNING,
                            target.id(), AuditAction.MAIL_SEND,
                            Map.of("number", source.basis().number(), "status", row.sent() ? "SENT" : "FAILED"));
                });

                return;
            } catch (DataAccessException e) {
                if (attempt >= LOG_ATTEMPTS) {
                    LOG.error("Could not log a mail about {} {} (message id {}, sent: {}); the mail proof is missing",
                            target.type(), target.id(), row.messageId(), row.sent());

                    throw e;
                }
            }
        }
    }

    /**
     * Notes that an offer was sent. The mail has already left and is logged, so a failure here is only logged: it
     * must not turn a sent mail into an error.
     */
    private void recordOfferSent(String offerId) {
        try {
            offers.record(offerId, OfferEventType.SENT);
        } catch (RuntimeException e) {
            LOG.error("Could not record that offer {} was sent", offerId, e);
        }
    }

    private MimeMessage build(JavaMailSender sender, List<InternetAddress> recipients, String subject, String body,
            String attachmentName, byte[] content) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED, "UTF-8");
            var from = senderAddress().orElseThrow();

            var fromName = company.find()
                    .map(CompanyInfo::name)
                    .orElse(null);

            helper.setFrom(new InternetAddress(from, fromName, "UTF-8"));
            helper.setTo(recipients.toArray(new InternetAddress[0]));

            if (!properties.replyTo().isBlank()) {
                helper.setReplyTo(properties.replyTo().strip());
            }

            var bcc = MailAddresses.parseConfigured(properties.bcc());

            if (!bcc.isEmpty()) {
                helper.setBcc(bcc.toArray(new InternetAddress[0]));
            }

            helper.setSubject(subject);
            helper.setText(renderer.text(body), renderer.html(subject, body));
            helper.addAttachment(attachmentName, new ByteArrayResource(content), "application/pdf");

            return message;
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new RuleViolationException("mail.error.build");
        }
    }

    private byte[] read(ArchivedFile file) {
        try {
            return archive.read(file);
        } catch (ArchiveIntegrityException e) {
            throw new RuleViolationException("mail.error.attachmentIntegrity");
        }
    }

    /**
     * Maps a failure to a message key. The text of the exception is never used: it can contain addresses.
     */
    private static String errorKey(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MailAuthenticationException
                    || cause instanceof AuthenticationFailedException) {
                return "mail.error.auth";
            }

            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException) {
                return "mail.error.connect";
            }

            if (cause instanceof SendFailedException) {
                return "mail.error.rejected";
            }
        }

        return "mail.error.smtp";
    }

    private Optional<String> senderAddress() {
        if (!properties.from().isBlank()) {
            return Optional.of(properties.from().strip());
        }

        return company.find()
                .map(info -> info.email())
                .filter(email -> email != null && !email.isBlank())
                .map(String::strip);
    }

    private Optional<ArchivedFile> pdf(MailTarget target) {
        var owner = target.type() == MailOwnerType.DOCUMENT ? ArchiveOwnerType.DOCUMENT : ArchiveOwnerType.DUNNING;

        return archive.findByOwner(owner, target.id())
                .stream()
                .filter(file -> file.type() == ArchiveFileType.PDF)
                .findFirst();
    }

    private String blockedKey(Optional<ArchivedFile> file) {
        var status = status();

        if (!status.available()) {
            return status.reasonKey();
        }

        if (file.isEmpty()) {
            return "mail.error.noAttachment";
        }

        return file.get().sizeBytes() > properties.maxAttachmentBytes() ? "mail.error.tooLarge" : null;
    }

    private static String prefilledRecipients(MailBasis basis) {
        if (basis.mailRecipients() != null && !basis.mailRecipients().isBlank()) {
            return basis.mailRecipients().strip();
        }

        if (basis.contactEmail() != null && !basis.contactEmail().isBlank()) {
            return basis.contactEmail().strip();
        }

        return basis.recipientEmail() == null ? "" : basis.recipientEmail().strip();
    }

    private String attachmentName(Source source) {
        return messages.getMessage("mail.attachment." + source.scope().name(), new Object[] {source.basis().number()},
                Locale.GERMAN);
    }

    private Source resolve(MailTarget target) {
        if (target.type() == MailOwnerType.DUNNING) {
            var basis = dunnings.mailBasis(target.id())
                    .orElseThrow(() -> new RuleViolationException("mail.error.notFound"));

            return new Source(MailScope.DUNNING, basis);
        }

        var invoice = invoices.find(target.id())
                .filter(info -> !info.isDraft())
                .orElseThrow(() -> new RuleViolationException("mail.error.notFinal"));
        var basis = letters.mailBasis(target.id())
                .orElseThrow(() -> new RuleViolationException("mail.error.notFinal"));

        var scope = switch (invoice.type()) {
            case CANCELLATION -> MailScope.CANCELLATION;
            case OFFER -> MailScope.OFFER;
            case INVOICE -> MailScope.INVOICE;
        };

        return new Source(scope, basis);
    }

    private record Source(MailScope scope, MailBasis basis) {
    }
}
