package de.cgoit.faktura.catalog;

/**
 * Unit of measure for catalog items and document items.
 *
 * @param code      short code shown to the user, e.g. {@code Std}
 * @param name      singular name, e.g. {@code Stunde}
 * @param namePlural plural name used for quantities other than 1, e.g. {@code Stunden}; {@code null} uses the name
 * @param uneceCode UN/ECE Recommendation 20 code used in the e-invoice, e.g. {@code HUR}
 */
public record UnitInfo(String id, String code, String name, String namePlural, String uneceCode) {
}
