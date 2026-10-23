package de.cgoit.faktura.shared;

/**
 * A violated rule of one value of an input record.
 *
 * @param field property of the input record, {@code null} if the rule concerns the input as a whole
 * @param key   message-bundle key
 */
public record FieldViolation(String field, String key) {
}
