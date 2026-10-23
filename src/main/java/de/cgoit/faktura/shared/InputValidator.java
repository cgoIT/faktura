package de.cgoit.faktura.shared;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

/**
 * Checks input records against their Bean Validation annotations. The message of an annotation is the key of the
 * message bundle in braces, for example {@code @Size(max = 200, message = "{validation.tooLong}")}; a violation turns
 * into that key, never into a translated text, so that services and web layer report the same rule the same way.
 * The property of a violation is the path from the input record, for example {@code subject} or, for the element of
 * a list annotated with {@code @Valid}, {@code items[2].description}. Rules that depend on other data (a unit that must exist, a VAT ID format per
 * country) stay in the services.
 */
@Component
public class InputValidator {

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");
    private static final Comparator<FieldViolation> ORDER = Comparator.comparing(InputValidator::sortKey)
            .thenComparing(FieldViolation::field)
            .thenComparing(FieldViolation::key);

    private final Validator validator;

    public InputValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * Lists all violations of the input, ordered by property path; the numbers of list indexes compare as numbers, so
     * {@code items[2]} comes before {@code items[10]}.
     */
    public List<FieldViolation> violations(Object input) {
        return validator.validate(input).stream()
                .map(InputValidator::toViolation)
                .sorted(ORDER)
                .toList();
    }

    /**
     * Checks the input and throws if a rule is violated. The exception carries all violations; the first one, ordered
     * by property, is its message and field.
     *
     * @throws RuleViolationException if a rule is violated
     */
    public void check(Object input) {
        var violations = violations(input);

        if (!violations.isEmpty()) {
            throw new RuleViolationException(violations);
        }
    }

    private static String sortKey(FieldViolation violation) {
        return INDEX.matcher(violation.field())
                .replaceAll(match -> "[%09d]".formatted(Long.parseLong(match.group(1))));
    }

    private static FieldViolation toViolation(ConstraintViolation<?> violation) {
        var template = violation.getMessageTemplate();
        var key = template.startsWith("{") && template.endsWith("}")
                ? template.substring(1, template.length() - 1)
                : template;

        return new FieldViolation(violation.getPropertyPath().toString(), key);
    }
}
