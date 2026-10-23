package de.cgoit.faktura.textblock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Editable values of a text template. The annotations are the rules for the values; the service checks them.
 *
 * @param makeDefault make this the default text of its scope and kind
 */
public record TextTemplateInput(
        @NotNull(message = "{validation.required}") TextScope scope,
        @NotNull(message = "{validation.required}") TextKind kind,
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String name,
        @NotBlank(message = "{validation.required}") @Size(max = 4000, message = "{validation.tooLong}") String body,
        boolean makeDefault) {
}
