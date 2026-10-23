package de.cgoit.faktura.textblock;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.internal.TextProperties;
import de.cgoit.faktura.textblock.internal.TextTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the reusable header and footer texts and fills their placeholders. Documents always keep their own copy of
 * the text: changing or deleting a template never changes an existing document.
 */
@Service
public class TextTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final String SALUTATION_MR = "MR";
    private static final String SALUTATION_MS = "MS";

    private final TextTemplateRepository templates;
    private final TextProperties properties;
    private final InputValidator validator;

    public TextTemplateService(TextTemplateRepository templates, TextProperties properties, InputValidator validator) {
        this.templates = templates;
        this.properties = properties;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<TextTemplateInfo> list() {
        return templates.findAll();
    }

    /**
     * Lists the templates of a scope and kind, ordered by name.
     */
    @Transactional(readOnly = true)
    public List<TextTemplateInfo> list(TextScope scope, TextKind kind) {
        return templates.find(scope, kind);
    }

    @Transactional(readOnly = true)
    public Optional<TextTemplateInfo> find(String id) {
        return templates.findById(id);
    }

    /**
     * Returns the text new documents of the scope start with, empty if no default is set.
     */
    @Transactional(readOnly = true)
    public Optional<String> defaultBody(TextScope scope, TextKind kind) {
        return templates.findDefault(scope, kind)
                .map(TextTemplateInfo::body);
    }

    /**
     * Creates a template. With {@code makeDefault} it replaces the default of its scope and kind.
     *
     * @throws RuleViolationException if a value is missing or too long or the text uses an unknown placeholder
     */
    @Transactional
    public TextTemplateInfo create(TextTemplateInput input) {
        var valid = validated(input);
        var id = UUID.randomUUID().toString();

        templates.insert(id, valid.scope(), valid.kind(), valid.name(), valid.body());
        applyDefault(id, valid);

        return templates.findById(id).orElseThrow();
    }

    /**
     * Changes name and text of a template. Scope and kind stay. Without {@code makeDefault} an existing default
     * status is kept.
     *
     * @throws RuleViolationException if the template is unknown or a value is invalid
     */
    @Transactional
    public TextTemplateInfo update(String id, TextTemplateInput input) {
        var existing = templates.findById(id)
                .orElseThrow(() -> new RuleViolationException("text.error.unknown"));
        var valid = validated(new TextTemplateInput(existing.scope(), existing.kind(), input.name(), input.body(),
                input.makeDefault()));

        templates.update(id, valid.name(), valid.body());
        applyDefault(id, valid);

        return templates.findById(id).orElseThrow();
    }

    @Transactional
    public void delete(String id) {
        templates.findById(id)
                .orElseThrow(() -> new RuleViolationException("text.error.unknown"));
        templates.delete(id);
    }

    /**
     * Replaces every placeholder in the text by its value; a placeholder without a value becomes empty.
     *
     * @throws RuleViolationException if the text contains an unknown placeholder
     */
    public String expand(String text, Map<Placeholder, String> values) {
        if (text == null) {
            return null;
        }

        requireKnownPlaceholders(text);

        var matcher = PLACEHOLDER.matcher(text);

        return matcher.replaceAll(result -> {
            var placeholder = Placeholder.ofKey(result.group(1)).orElseThrow();

            return Matcher.quoteReplacement(values.getOrDefault(placeholder, ""));
        });
    }

    /**
     * Checks that a text only uses known placeholders.
     *
     * @throws RuleViolationException if the text contains an unknown placeholder
     */
    public void validate(String text) {
        if (text != null) {
            requireKnownPlaceholders(text);
        }
    }

    /**
     * Builds the salutation line for a contact person.
     *
     * @param salutation {@code MR}, {@code MS} or {@code null}/blank for none
     * @param lastName   last name for the formal salutation, may be {@code null}
     * @param fullName   full name of the contact, {@code null} if the document has no contact person
     */
    public String salutation(String salutation, String lastName, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return properties.salutationGeneric();
        }

        var formalName = lastName == null || lastName.isBlank() ? lastWord(fullName) : lastName.strip();

        if (SALUTATION_MR.equals(salutation)) {
            return format(properties.salutationMr(), formalName);
        }

        if (SALUTATION_MS.equals(salutation)) {
            return format(properties.salutationMs(), formalName);
        }

        return format(properties.salutationNamed(), fullName.strip());
    }

    private void applyDefault(String id, TextTemplateInput input) {
        if (input.makeDefault()) {
            templates.clearDefault(input.scope(), input.kind());
            templates.markDefault(id);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private TextTemplateInput validated(TextTemplateInput input) {
        var normalized = new TextTemplateInput(input.scope(), input.kind(), blankToNull(input.name()),
                blankToNull(input.body()), input.makeDefault());

        validator.check(normalized);
        requireKnownPlaceholders(normalized.body());

        return normalized;
    }

    private static void requireKnownPlaceholders(String text) {
        var matcher = PLACEHOLDER.matcher(text);

        while (matcher.find()) {
            if (Placeholder.ofKey(matcher.group(1)).isEmpty()) {
                throw new RuleViolationException("text.error.unknownPlaceholder");
            }
        }
    }

    private static String lastWord(String name) {
        var parts = name.strip().split("\\s+");

        return parts[parts.length - 1];
    }

    private static String format(String pattern, String name) {
        return new MessageFormat(pattern, Locale.GERMANY).format(new Object[] {name});
    }
}
