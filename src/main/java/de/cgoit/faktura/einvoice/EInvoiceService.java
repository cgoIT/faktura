package de.cgoit.faktura.einvoice;

import de.cgoit.faktura.einvoice.internal.ZugferdFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a rendered PDF/A-1b and the calculated invoice data into a validated ZUGFeRD invoice (PDF/A-3 with embedded
 * EN 16931 XML). Nothing is returned that did not pass the Mustang validator.
 */
@Service
public class EInvoiceService {

    private final ZugferdFactory factory;

    public EInvoiceService(EInvoiceProperties properties) {
        this.factory = new ZugferdFactory(properties);
    }

    /**
     * Creates the XML from the given data, embeds it into the PDF and validates the result. The amounts in the XML must
     * equal the amounts of the document exactly.
     *
     * @param pdfA1 the rendered invoice as PDF/A-1b
     * @throws EInvoiceException if the amounts differ or the validator reports errors; the message contains only
     *                           rule identifiers
     */
    public EInvoiceResult embed(byte[] pdfA1, EInvoiceDocument document) {
        return factory.create(pdfA1, document);
    }
}
