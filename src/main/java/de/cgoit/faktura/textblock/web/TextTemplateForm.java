package de.cgoit.faktura.textblock.web;

import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateInput;

/**
 * Form data of a text template; rules are checked by the service.
 */
public record TextTemplateForm(TextScope scope, TextKind kind, String name, String body, Boolean makeDefault) {

    static TextTemplateForm empty() {
        return new TextTemplateForm(TextScope.INVOICE, TextKind.HEADER, "", "", Boolean.FALSE);
    }

    TextTemplateInput toInput() {
        return new TextTemplateInput(scope, kind, name, body, Boolean.TRUE.equals(makeDefault));
    }
}
