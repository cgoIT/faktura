package de.cgoit.faktura.einvoice.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import de.cgoit.faktura.einvoice.EInvoiceDocument;
import de.cgoit.faktura.einvoice.EInvoiceException;
import de.cgoit.faktura.einvoice.EInvoiceProperties;
import de.cgoit.faktura.einvoice.EInvoiceResult;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA1;
import org.mustangproject.validator.ZUGFeRDValidator;

/**
 * Creates and validates ZUGFeRD invoices with Mustang.
 */
public class ZugferdFactory {

    private final EInvoiceProperties properties;

    public ZugferdFactory(EInvoiceProperties properties) {
        this.properties = properties;
    }

    private static final String PRODUCER = "faktura";

    /**
     * Creates the XML, checks its amounts against the document, embeds it and validates the PDF. Any failure,
     * including a crash inside Mustang, becomes an {@link EInvoiceException}; only its type is kept as cause text.
     */
    public EInvoiceResult create(byte[] pdfA1, EInvoiceDocument document) {
        try {
            var xml = xml(document);

            TotalsCheck.verify(xml, document);

            var pdf = embed(pdfA1, xml);

            ValidationReport.parse(new ZUGFeRDValidator().validate(pdf, "invoice.pdf"))
                    .requireValid();

            return new EInvoiceResult(pdf, xml);
        } catch (EInvoiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EInvoiceException("E-invoice creation failed: " + e.getClass().getSimpleName(), List.of());
        }
    }

    private byte[] xml(EInvoiceDocument document) {
        var provider = new ZUGFeRD2PullProvider();

        provider.setProfile(Profiles.getByName("EN16931"));
        provider.generateXML(MustangMapper.map(document, properties.discountReason()));

        return provider.getXML();
    }

    private static byte[] embed(byte[] pdfA1, byte[] xml) {
        try (var exporter = new ZUGFeRDExporterFromA1()) {
            var output = new ByteArrayOutputStream();

            exporter.ignorePDFAErrors();
            exporter.load(pdfA1);
            exporter.setProducer(PRODUCER);
            exporter.setCreator(PRODUCER);
            exporter.setProfile(Profiles.getByName("EN16931"));
            exporter.setXML(xml);
            exporter.export(output);

            return output.toByteArray();
        } catch (IOException e) {
            throw new EInvoiceException("Embedding the XML into the PDF failed", e);
        }
    }
}
