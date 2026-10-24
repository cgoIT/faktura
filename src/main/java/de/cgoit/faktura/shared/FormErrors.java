package de.cgoit.faktura.shared;

import java.util.function.UnaryOperator;

import org.springframework.beans.InvalidPropertyException;
import org.springframework.validation.BindingResult;

/**
 * Puts the violations of a service rule at the fields of a form.
 */
public final class FormErrors {

    private FormErrors() {
    }

    /**
     * Puts every violation at its field of the form. Returns the key of the notice above the form: the first violation
     * without a field of its own, else the general hint to check the marked fields.
     *
     * @param formField maps the property of a violation to the form field, {@code null} for none
     * @param fallback  key of the general hint
     */
    public static String reject(RuleViolationException e, BindingResult result, UnaryOperator<String> formField,
            String fallback) {
        String notice = null;

        for (var violation : e.violations()) {
            var field = formField.apply(violation.field());

            if (field == null || !rejectValue(result, field, violation.key())) {
                notice = notice != null ? notice : violation.key();
            }
        }

        return notice != null ? notice : fallback;
    }

    private static boolean rejectValue(BindingResult result, String field, String key) {
        try {
            result.rejectValue(field, key);

            return true;
        } catch (InvalidPropertyException e) {
            return false;
        }
    }
}
