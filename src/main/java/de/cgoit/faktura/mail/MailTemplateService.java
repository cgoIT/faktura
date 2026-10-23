package de.cgoit.faktura.mail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.mail.internal.MailTemplateRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the reusable mail texts. A document type can have any number of variants, for example a formal and an
 * informal one; one of them is the default of the send dialog. A mail always keeps its own copy of the text: changing
 * or deleting a template never changes a mail that was sent.
 */
@Service
public class MailTemplateService {

    private final MailTemplateRepository templates;
    private final TextTemplateService texts;
    private final InputValidator validator;

    public MailTemplateService(MailTemplateRepository templates, TextTemplateService texts, InputValidator validator) {
        this.templates = templates;
        this.texts = texts;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<MailTemplateInfo> list() {
        return templates.findAll();
    }

    /**
     * Lists the templates of a document type, ordered by name.
     */
    @Transactional(readOnly = true)
    public List<MailTemplateInfo> list(MailScope scope) {
        return templates.find(scope);
    }

    @Transactional(readOnly = true)
    public Optional<MailTemplateInfo> find(String id) {
        return templates.findById(id);
    }

    /**
     * Returns the template the send dialog starts with, empty if the document type has no default.
     */
    @Transactional(readOnly = true)
    public Optional<MailTemplateInfo> defaultFor(MailScope scope) {
        return templates.findDefault(scope);
    }

    /**
     * Creates a template. With {@code makeDefault} it replaces the default of its document type.
     *
     * @throws RuleViolationException if a value is missing or too long, subject or text use an unknown placeholder,
     *                                or the document type is a system template, which exists exactly once
     */
    @Transactional
    public MailTemplateInfo create(MailTemplateInput input) {
        if (input.scope() != null && input.scope().system()) {
            throw new RuleViolationException("mail.template.error.system");
        }

        var valid = validated(input);
        var id = UUID.randomUUID().toString();

        templates.insert(id, valid.scope(), valid.name(), valid.subject(), valid.body());
        applyDefault(id, valid);

        return templates.findById(id).orElseThrow();
    }

    /**
     * Changes name, subject and text of a template. The document type stays. Without {@code makeDefault} an existing
     * default status is kept.
     *
     * @throws RuleViolationException if the template is unknown or a value is invalid
     */
    @Transactional
    public MailTemplateInfo update(String id, MailTemplateInput input) {
        var existing = templates.findById(id)
                .orElseThrow(() -> new RuleViolationException("mail.template.error.unknown"));
        var valid = validated(new MailTemplateInput(existing.scope(), input.name(), input.subject(), input.body(),
                input.makeDefault()));

        templates.update(id, valid.name(), valid.subject(), valid.body());
        applyDefault(id, valid);

        return templates.findById(id).orElseThrow();
    }

    /**
     * Deletes a template.
     *
     * @throws RuleViolationException if the template is unknown or a system template
     */
    @Transactional
    public void delete(String id) {
        var existing = templates.findById(id)
                .orElseThrow(() -> new RuleViolationException("mail.template.error.unknown"));

        if (existing.scope().system()) {
            throw new RuleViolationException("mail.template.error.system");
        }

        templates.delete(id);
    }

    private void applyDefault(String id, MailTemplateInput input) {
        if (input.makeDefault()) {
            templates.clearDefault(input.scope());
            templates.markDefault(id);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private MailTemplateInput validated(MailTemplateInput input) {
        var normalized = new MailTemplateInput(input.scope(), blankToNull(input.name()), blankToNull(input.subject()),
                blankToNull(input.body()), input.makeDefault());

        validator.check(normalized);
        texts.validate(normalized.subject());
        texts.validate(normalized.body());

        return normalized;
    }
}
