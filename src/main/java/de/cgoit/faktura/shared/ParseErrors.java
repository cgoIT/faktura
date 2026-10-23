package de.cgoit.faktura.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects the errors of converting form text to numbers, so that a form reports every unreadable value at its field
 * (for example {@code items[1].unitPriceCents}) instead of stopping at the first one.
 */
public final class ParseErrors {

    private final List<FieldViolation> violations = new ArrayList<>();

    /**
     * Runs the parser and returns its result; if it throws a {@link RuleViolationException}, the violation is
     * recorded for the given property and the fallback is returned.
     */
    public <T> T parse(String field, Supplier<T> parser, T fallback) {
        try {
            return parser.get();
        } catch (RuleViolationException e) {
            violations.add(new FieldViolation(field, e.messageKey()));

            return fallback;
        }
    }

    /**
     * Throws all recorded errors at once.
     *
     * @throws RuleViolationException if at least one value could not be read
     */
    public void throwIfAny() {
        if (!violations.isEmpty()) {
            throw new RuleViolationException(violations);
        }
    }
}
