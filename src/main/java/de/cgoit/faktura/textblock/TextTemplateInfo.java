package de.cgoit.faktura.textblock;

/**
 * A reusable header or footer text with placeholders.
 *
 * @param isDefault {@code true} if new documents of the scope start with this text
 */
public record TextTemplateInfo(String id, TextScope scope, TextKind kind, String name, String body,
        boolean isDefault) {
}
