package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.Placeholder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class MailTemplateServiceTest {

    @Autowired
    private MailTemplateService templates;

    @Autowired
    private MessageSource messages;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void restoreSeed() {
        jdbc.sql("DELETE FROM mail_template WHERE id NOT LIKE '30000000-%'").update();
        jdbc.sql("UPDATE mail_template SET is_default = 0").update();
        jdbc.sql("UPDATE mail_template SET is_default = 1 WHERE name = 'Formell' OR scope = 'NOTIFICATION_DIGEST'").update();
    }

    private static MailTemplateInput input(String name, String subject, String body, boolean makeDefault) {
        return new MailTemplateInput(MailScope.INVOICE, name, subject, body, makeDefault);
    }

    @Test
    void anyNumberOfVariantsPerDocumentTypeAndOneDefault() {
        var created = templates.create(input("Kurz", "Rechnung {rechnungsnummer}", "{anrede}\n\nanbei die Rechnung.",
                true));

        assertThat(templates.list(MailScope.INVOICE)).extracting(MailTemplateInfo::name)
                .containsExactly("Formell", "Informell", "Kurz");
        assertThat(templates.defaultFor(MailScope.INVOICE).orElseThrow().id()).isEqualTo(created.id());
        assertThat(templates.list(MailScope.INVOICE).stream().filter(MailTemplateInfo::isDefault)).hasSize(1);
        assertThat(templates.defaultFor(MailScope.DUNNING).orElseThrow().name()).isEqualTo("Formell");
    }

    @Test
    void updateKeepsScopeAndDefaultStatus() {
        var created = templates.create(input("Kurz", "Betreff", "Text", true));

        var changed = templates.update(created.id(), new MailTemplateInput(MailScope.DUNNING, "Kürzer", "Neu",
                "Neuer Text {kunde}", false));

        assertThat(changed.scope()).isEqualTo(MailScope.INVOICE);
        assertThat(changed.name()).isEqualTo("Kürzer");
        assertThat(changed.isDefault()).isTrue();
    }

    @Test
    void unknownPlaceholdersInSubjectAndBodyAreRejected() {
        assertThatThrownBy(() -> templates.create(input("A", "Hallo {gibtsnicht}", "Text", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("text.error.unknownPlaceholder");
        assertThatThrownBy(() -> templates.create(input("A", "Betreff", "Hallo {gibtsnicht}", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("text.error.unknownPlaceholder");
    }

    @Test
    void valuesAreValidatedByTheirAnnotations() {
        assertThatThrownBy(() -> templates.create(input(" ", "Betreff", "Text", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.required");
        assertThatThrownBy(() -> templates.create(input("A", "x".repeat(201), "Text", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.tooLong");
        assertThatThrownBy(() -> templates.create(input("A", "zwei\nZeilen", "Text", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.subjectLine");
    }

    @Test
    void deleteRemovesTheTemplateAndUnknownIdsAreReported() {
        var created = templates.create(input("Kurz", "Betreff", "Text", false));

        templates.delete(created.id());

        assertThat(templates.find(created.id())).isEmpty();
        assertThatThrownBy(() -> templates.delete(created.id())).isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.template.error.unknown");
    }

    @Test
    void seededTextsOnlyUseKnownPlaceholders() {
        for (var template : templates.list()) {
            assertThat(template.subject()).isNotBlank();
            assertThat(template.body()).isNotBlank();
        }

        assertThat(templates.list()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void everyPlaceholderHasASampleValueAndAHelpText() {
        for (var placeholder : Placeholder.values()) {
            assertThat(messages.getMessage("mail.sample." + placeholder.key(), null, null, Locale.GERMAN))
                    .as("sample for %s", placeholder.key())
                    .isNotNull();
            assertThat(messages.getMessage("text.placeholder." + placeholder.key(), null, null, Locale.GERMAN))
                    .as("help for %s", placeholder.key())
                    .isNotNull();
        }
    }

    @Test
    void theDigestTemplateExistsOnceAndCannotBeCreatedOrDeleted() {
        var digest = templates.defaultFor(MailScope.NOTIFICATION_DIGEST).orElseThrow();

        assertThat(templates.list(MailScope.NOTIFICATION_DIGEST)).hasSize(1);
        assertThatThrownBy(() -> templates.create(new MailTemplateInput(MailScope.NOTIFICATION_DIGEST, "Zweite",
                "Betreff", "Text", true))).hasMessage("mail.template.error.system");
        assertThatThrownBy(() -> templates.delete(digest.id())).hasMessage("mail.template.error.system");
    }

    @Test
    void theDigestTemplateCanBeEditedAndStaysTheDefault() {
        var digest = templates.defaultFor(MailScope.NOTIFICATION_DIGEST).orElseThrow();

        try {
            var changed = templates.update(digest.id(), new MailTemplateInput(MailScope.NOTIFICATION_DIGEST,
                    "Hinweise", "Neu: {anzahl}", "{entwuerfe}\n\n{link}", false));

            assertThat(changed.isDefault()).isTrue();
            assertThat(changed.subject()).isEqualTo("Neu: {anzahl}");
        } finally {
            // the system template is shared with the notification tests, which expect the seeded text
            templates.update(digest.id(), new MailTemplateInput(MailScope.NOTIFICATION_DIGEST, digest.name(),
                    digest.subject(), digest.body(), false));
        }
    }

    @Test
    void theDigestTemplateRejectsUnknownPlaceholders() {
        var digest = templates.defaultFor(MailScope.NOTIFICATION_DIGEST).orElseThrow();

        assertThatThrownBy(() -> templates.update(digest.id(), new MailTemplateInput(MailScope.NOTIFICATION_DIGEST,
                "Hinweise", "Betreff", "{gibtesnicht}", false))).isInstanceOf(RuleViolationException.class);
    }
}
