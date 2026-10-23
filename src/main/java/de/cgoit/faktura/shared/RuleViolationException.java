package de.cgoit.faktura.shared;

import java.util.List;

/**
 * Signals that user input violates a business rule. The message is a message-bundle key so that the web layer
 * can show a translated text. Optional details are further identifiers (for example rule ids of a validator) that
 * the web layer may translate; they never contain personal data. A violation of a single input value names the
 * property of the input record, so that the web layer can show it at the right field.
 */
public class RuleViolationException extends RuntimeException {

    private final List<String> details;
    private final String field;
    private final List<FieldViolation> violations;

    public RuleViolationException(String messageKey) {
        this(messageKey, List.of());
    }

    public RuleViolationException(String messageKey, List<String> details) {
        super(messageKey);
        this.details = List.copyOf(details);
        this.field = null;
        this.violations = List.of(new FieldViolation(null, messageKey));
    }

    /**
     * Creates a violation of the named property of an input record.
     */
    public RuleViolationException(String messageKey, String field) {
        super(messageKey);
        this.details = List.of();
        this.field = field;
        this.violations = List.of(new FieldViolation(field, messageKey));
    }

    /**
     * Creates a violation of several values of an input record at once. The first one is the primary violation.
     *
     * @throws IllegalArgumentException if the list is empty
     */
    public RuleViolationException(List<FieldViolation> violations) {
        super(violations.getFirst().key());
        this.details = List.of();
        this.field = violations.getFirst().field();
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the message-bundle key describing the violated rule.
     */
    public String messageKey() {
        return getMessage();
    }

    /**
     * Returns identifiers that explain the violation in more detail, possibly empty.
     */
    public List<String> details() {
        return details;
    }

    /**
     * Returns all violated rules; there is one entry unless the input record was checked as a whole.
     */
    public List<FieldViolation> violations() {
        return violations;
    }

    /**
     * Returns the property of the input record that was violated, or {@code null} if the rule concerns the input as
     * a whole.
     */
    public String field() {
        return field;
    }
}
