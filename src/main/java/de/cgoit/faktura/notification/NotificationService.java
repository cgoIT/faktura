package de.cgoit.faktura.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailService;
import de.cgoit.faktura.mail.MailTemplateService;
import de.cgoit.faktura.notification.DigestResult.Outcome;
import de.cgoit.faktura.notification.internal.DigestBuilder;
import de.cgoit.faktura.notification.internal.NotificationProperties;
import de.cgoit.faktura.notification.internal.NotificationRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells the operator once a day by mail what needs attention: new drafts from recurring plans, plans that failed,
 * overdue invoices and invoices for which a dunning letter is due. The mail goes to the operator only, never to a
 * customer, and one mail carries all categories. Without anything new no mail is sent. Every category is off until
 * the user switches it on.
 *
 * <p>What to report is computed from the current data and filtered by the log of what earlier mails reported, so a
 * second run, a restart or a crash cannot report an item twice, and an item whose mail failed is reported by the next
 * run. The mail is sent outside any database transaction (the connection pool of SQLite has a single connection);
 * the log is written afterwards. If the process dies between the two, the next run repeats that mail.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    private static final int LOG_ATTEMPTS = 2;

    private final NotificationRepository repository;
    private final DigestBuilder builder;
    private final MailService mail;
    private final MailTemplateService mailTemplates;
    private final TextTemplateService texts;
    private final CompanyService company;
    private final InputValidator validator;
    private final MessageSource messages;
    private final NotificationProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();
    private volatile Instant lastFailure;

    public NotificationService(NotificationRepository repository, DigestBuilder builder, MailService mail,
            MailTemplateService mailTemplates, TextTemplateService texts, CompanyService company,
            InputValidator validator, MessageSource messages, NotificationProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.builder = builder;
        this.mail = mail;
        this.mailTemplates = mailTemplates;
        this.texts = texts;
        this.company = company;
        this.validator = validator;
        this.messages = messages;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationSettings settings() {
        return repository.settings();
    }

    /**
     * Returns the address the mail goes to: the chosen one, else the e-mail address of the company, empty if there
     * is none.
     */
    @Transactional(readOnly = true)
    public Optional<String> recipient() {
        return recipientOf(repository.settings());
    }

    /**
     * Returns the time of day of the daily mail: the chosen one, else the configured default.
     */
    @Transactional(readOnly = true)
    public LocalTime sendTime() {
        return sendTimeOf(repository.settings());
    }

    /**
     * Tells whether the automatic run is paused because too many runs in a row failed to send the mail.
     */
    @Transactional(readOnly = true)
    public boolean paused() {
        return repository.settings().failureCount() >= properties.maxFailures();
    }

    /**
     * Saves the settings and clears the failure counter, so a paused automatic run starts again.
     *
     * @throws RuleViolationException if a value is invalid or a category is switched on although there is no
     *                                address to send to
     */
    @Transactional
    public NotificationSettings update(NotificationInput input) {
        var normalized = new NotificationInput(input.draftEnabled(), input.planErrorEnabled(),
                input.overdueEnabled(), input.dunningEnabled(), input.offerEnabled(), blankToNull(input.recipient()),
                blankToNull(input.sendTime()));

        validator.check(normalized);

        var recipient = normalized.recipient() == null ? null
                : mail.normalizeAddress(normalized.recipient(), "recipient");
        var anyEnabled = normalized.draftEnabled() || normalized.planErrorEnabled() || normalized.overdueEnabled()
                || normalized.dunningEnabled() || normalized.offerEnabled();

        if (anyEnabled && recipient == null && companyEmail().isEmpty()) {
            throw new RuleViolationException("notification.error.noRecipient", "recipient");
        }

        repository.updateSettings(normalized.draftEnabled(), normalized.planErrorEnabled(),
                normalized.overdueEnabled(), normalized.dunningEnabled(), normalized.offerEnabled(), recipient,
                normalized.sendTime());

        return repository.settings();
    }

    /**
     * Runs the scheduled check: sends the mail if it is due, that is, a category is on, the time of day has been
     * reached, today's run has not happened yet and the automatic run is not paused.
     *
     * @return the result, {@code null} if nothing was due
     */
    public DigestResult runIfDue(LocalDateTime now) {
        var settings = repository.settings();

        if (!settings.anyEnabled()
                || today(now).equals(settings.lastRunDate())
                || now.toLocalTime().isBefore(sendTimeOf(settings))) {
            return null;
        }

        var failure = lastFailure;

        if (failure != null && Instant.now(clock).isBefore(failure.plus(properties.retryDelay()))) {
            return null;
        }

        return run(now.toLocalDate(), false);
    }

    /**
     * Checks and sends now, whatever the time of day, for the button "check now".
     */
    public DigestResult runNow() {
        return run(LocalDate.now(clock), true);
    }

    /**
     * Sends a fixed test text to the recipient, nothing is logged.
     *
     * @throws RuleViolationException if mail is not available, there is no recipient or the server refused the mail
     */
    public void sendTest() {
        var recipient = recipientOf(repository.settings())
                .orElseThrow(() -> new RuleViolationException("notification.error.noRecipient", "recipient"));

        mail.sendNotice(recipient, messages.getMessage("notification.test.subject", null, Locale.GERMAN),
                messages.getMessage("notification.test.body", null, Locale.GERMAN));
        repository.resetFailures();
        lastFailure = null;
    }

    /**
     * Runs one check at a time: the scheduler and the button must not both send the same items.
     */
    private DigestResult run(LocalDate today, boolean manual) {
        if (!running.tryLock()) {
            return new DigestResult(Outcome.BLOCKED, 0, "notification.error.running");
        }

        try {
            return check(today, manual);
        } finally {
            running.unlock();
        }
    }

    private DigestResult check(LocalDate today, boolean manual) {
        var settings = repository.settings();

        if (!settings.anyEnabled()) {
            return DigestResult.of(Outcome.DISABLED);
        }

        if (!manual && settings.failureCount() >= properties.maxFailures()) {
            return DigestResult.of(Outcome.PAUSED);
        }

        var status = mail.status();

        if (!status.available()) {
            return new DigestResult(Outcome.BLOCKED, 0, status.reasonKey());
        }

        var recipient = recipientOf(settings);

        if (recipient.isEmpty()) {
            return new DigestResult(Outcome.BLOCKED, 0, "notification.error.noRecipient");
        }

        var template = mailTemplates.defaultFor(MailScope.NOTIFICATION_DIGEST);

        if (template.isEmpty()) {
            return new DigestResult(Outcome.BLOCKED, 0, "notification.error.noTemplate");
        }

        var digest = builder.build(settings, today);

        if (digest.isEmpty()) {
            if (!manual) {
                repository.markRun(today);
            }

            return DigestResult.of(Outcome.NOTHING_TO_REPORT);
        }

        try {
            mail.sendNotice(recipient.get(), texts.expand(template.get().subject(), digest.values()),
                    texts.expand(template.get().body(), digest.values()));
        } catch (RuleViolationException e) {
            repository.countFailure();
            lastFailure = Instant.now(clock);
            LOG.warn("Notification mail not sent: {}", e.messageKey());

            return new DigestResult(Outcome.FAILED, 0, e.messageKey());
        } catch (RuntimeException e) {
            repository.countFailure();
            lastFailure = Instant.now(clock);
            LOG.warn("Notification mail not sent: {}", e.getClass().getSimpleName());

            return new DigestResult(Outcome.FAILED, 0, "notification.error.unexpected");
        }

        lastFailure = null;
        logReported(digest.items(), today, manual);

        return new DigestResult(Outcome.SENT, digest.items().size(), null);
    }

    /**
     * Writes the log and, for a scheduled run, the day of the run in one short transaction, after the mail has left. A failure is retried
     * once; if it persists, the mail is repeated by the next run, which is logged without personal data.
     */
    private void logReported(List<NotificationRepository.Item> items, LocalDate today, boolean manual) {
        for (var attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(status -> {
                    repository.log(items);

                    if (manual) {
                        repository.resetFailures();
                    } else {
                        repository.markRun(today);
                    }
                });

                return;
            } catch (RuntimeException e) {
                if (attempt >= LOG_ATTEMPTS) {
                    LOG.error("Could not log {} reported notification items; the next run reports them again",
                            items.size());

                    return;
                }
            }
        }
    }

    private Optional<String> recipientOf(NotificationSettings settings) {
        if (settings.recipient() != null && !settings.recipient().isBlank()) {
            return Optional.of(settings.recipient());
        }

        return companyEmail();
    }

    private Optional<String> companyEmail() {
        return company.find()
                .map(CompanyInfo::email)
                .filter(email -> email != null && !email.isBlank())
                .map(String::strip);
    }

    private LocalTime sendTimeOf(NotificationSettings settings) {
        return LocalTime.parse(settings.sendTime() != null ? settings.sendTime() : properties.defaultSendTime());
    }

    private static LocalDate today(LocalDateTime now) {
        return now.toLocalDate();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
