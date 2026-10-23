package de.cgoit.faktura.tax;

/**
 * Kind of taxation of the company.
 */
public enum TaxRegime {
    /** Small business according to section 19 UStG: no VAT is shown. */
    SMALL_BUSINESS,
    /** Regular taxation with VAT shown per rate. */
    REGULAR
}
