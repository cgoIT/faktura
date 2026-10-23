package de.cgoit.faktura.einvoice;

/**
 * A validated ZUGFeRD invoice: the PDF/A-3 with the embedded XML and the XML itself.
 */
public record EInvoiceResult(byte[] pdf, byte[] xml) {
}
