package de.cgoit.faktura.einvoice;

import java.util.List;

/**
 * Signals that an electronic invoice could not be created or did not pass validation. The message names only rule
 * identifiers, never texts or values of the validator, because those can contain customer data.
 */
public class EInvoiceException extends RuntimeException {

    private final List<String> rules;

    public EInvoiceException(String message, List<String> rules) {
        super(message + (rules.isEmpty() ? "" : " " + rules));
        this.rules = List.copyOf(rules);
    }

    public EInvoiceException(String message, Throwable cause) {
        super(message, cause);
        this.rules = List.of();
    }

    /**
     * Returns the identifiers of the violated rules, e.g. {@code BR-CO-10}.
     */
    public List<String> rules() {
        return rules;
    }
}
