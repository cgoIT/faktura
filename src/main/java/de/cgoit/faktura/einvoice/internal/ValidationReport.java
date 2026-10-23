package de.cgoit.faktura.einvoice.internal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.cgoit.faktura.einvoice.EInvoiceException;

/**
 * Reads the XML report of the Mustang validator. Only errors count; notices and warnings (for example the hints of
 * the XRechnung rules, which this program does not claim to satisfy) are ignored. The report can contain customer
 * data, so only rule identifiers leave this class.
 */
final class ValidationReport {

    private static final Pattern RULE_ID = Pattern.compile("\\[(?:ID )?([A-Za-z][A-Za-z0-9-]*-[A-Za-z0-9-]+)]");
    private static final Set<String> ERROR_ELEMENTS = Set.of("error", "fatal");

    private final boolean invalid;
    private final List<String> rules;

    private ValidationReport(boolean invalid, List<String> rules) {
        this.invalid = invalid;
        this.rules = rules;
    }

    static ValidationReport parse(String report) {
        var document = XmlReader.parse(report.getBytes(StandardCharsets.UTF_8));
        var rules = new LinkedHashSet<String>();
        var errors = 0;

        for (var name : ERROR_ELEMENTS) {
            for (var element : XmlReader.descendants(document, name)) {
                errors++;

                var matcher = RULE_ID.matcher(element.getTextContent());

                rules.add(matcher.find() ? matcher.group(1) : "type-" + element.getAttribute("type"));
            }
        }

        var invalid = errors > 0 || XmlReader.descendants(document, "summary").stream()
                .anyMatch(summary -> "invalid".equals(summary.getAttribute("status")));

        return new ValidationReport(invalid, List.copyOf(rules));
    }

    /**
     * Throws if the report contains errors.
     *
     * @throws EInvoiceException with the rule identifiers
     */
    void requireValid() {
        if (invalid) {
            throw new EInvoiceException("The e-invoice failed validation", rules);
        }
    }
}
