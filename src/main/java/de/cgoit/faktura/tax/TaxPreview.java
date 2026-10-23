package de.cgoit.faktura.tax;

/**
 * Effect of a tax profile on an example amount, used to explain a profile before it is saved.
 */
public record TaxPreview(long netCents, long taxCents, long grossCents, TaxDefaults defaults) {
}
