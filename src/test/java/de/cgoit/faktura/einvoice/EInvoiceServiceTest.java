package de.cgoit.faktura.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.einvoice.EInvoiceDocument.Bank;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Charge;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Line;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Party;
import de.cgoit.faktura.einvoice.EInvoiceDocument.TaxGroup;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Type;
import de.cgoit.faktura.render.RenderFixtures;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mustangproject.validator.ZUGFeRDValidator;

/**
 * Creates ZUGFeRD invoices from invented data and lets the Mustang validator check the resulting PDF. The service
 * itself already validates; the tests here repeat the validation independently and assert on the XML content.
 */
class EInvoiceServiceTest {

    private static final String EXEMPTION = "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.";

    private static byte[] pdfA1;

    private final EInvoiceService service = new EInvoiceService(new EInvoiceProperties("DE", "H87", "Rabatt"));

    @BeforeAll
    static void renderPdf() {
        pdfA1 = RenderFixtures.renderer()
                .renderPdf(RenderFixtures.regularInvoice());
    }

    @Test
    void smallBusinessWithItemAndTotalDiscountMatchesTheExampleOfTheDataModel() throws IOException {
        var lines = List.of(
                new Line(1, "Webentwicklung", 6000, "HUR", 8500, 1000, "E", 0, 45900),
                new Line(2, "Domain-Registrierung", 1000, "H87", 1200, 0, "E", 0, 1200),
                new Line(3, "Anfahrt", 1000, "LS", 4000, 0, "E", 0, 4000));
        var document = invoice(Type.INVOICE, lines,
                List.of(new TaxGroup("E", 0, 48545, 0, 2555, EXEMPTION)), 51100, 2555, 48545, 0, 48545);

        var result = service.embed(pdfA1, document);
        var xml = new String(result.xml(), StandardCharsets.UTF_8);

        assertThat(xml).contains("<ram:TypeCode>380</ram:TypeCode>")
                .contains("<ram:GrandTotalAmount>485.45</ram:GrandTotalAmount>")
                .contains("<ram:AllowanceTotalAmount>25.55</ram:AllowanceTotalAmount>")
                .contains("<ram:CategoryCode>E</ram:CategoryCode>")
                .contains("<udt:DateTimeString format=\"102\">20270115</udt:DateTimeString>")
                .contains(EXEMPTION);
        assertValid(result);
        assertEmbedded(result);
    }

    @Test
    void regularTaxationWithTwoRatesDistributesTheTotalDiscount() throws IOException {
        var lines = List.of(
                new Line(1, "Beratung", 1000, "DAY", 100000, 0, "S", 1900, 100000),
                new Line(2, "Schulungsunterlagen", 1000, "H87", 10000, 0, "S", 700, 10000));
        var groups = List.of(
                new TaxGroup("S", 1900, 90000, 17100, 10000, null),
                new TaxGroup("S", 700, 9000, 630, 1000, null));
        var document = invoice(Type.INVOICE, lines, groups, 110000, 11000, 99000, 17730, 116730);

        var result = service.embed(pdfA1, document);

        assertThat(new String(result.xml(), StandardCharsets.UTF_8))
                .contains("<ram:GrandTotalAmount>1167.30</ram:GrandTotalAmount>")
                .contains("<ram:TaxTotalAmount currencyID=\"EUR\">177.30</ram:TaxTotalAmount>");
        assertValid(result);
    }

    @Test
    void zeroRatedAndReverseChargeCategoriesAreValid() throws IOException {
        var lines = List.of(
                new Line(1, "Lieferung", 2000, "H87", 5000, 0, "S", 1900, 10000),
                new Line(2, "Leistung ohne Steuer", 1000, "LS", 7000, 0, "Z", 0, 7000),
                new Line(3, "Bauleistung", 1000, "LS", 30000, 0, "AE", 0, 30000));
        var groups = List.of(
                new TaxGroup("S", 1900, 10000, 1900, 0, null),
                new TaxGroup("Z", 0, 7000, 0, 0, null),
                new TaxGroup("AE", 0, 30000, 0, 0, "Steuerschuldnerschaft des Leistungsempfängers"));
        var document = invoice(Type.INVOICE, lines, groups, 47000, 0, 47000, 1900, 48900);

        assertValid(service.embed(pdfA1, document));
    }

    @Test
    void cancellationBecomesACreditNoteReferringToTheInvoice() throws IOException {
        var lines = List.of(new Line(1, "Beratung", 2000, "HUR", 9000, 0, "E", 0, 18000));
        var document = new EInvoiceDocument(Type.CREDIT_NOTE, "ST202700001", LocalDate.of(2027, 2, 3), null, null,
                null, seller(), buyer(), bank(), lines, List.of(new TaxGroup("E", 0, 18000, 0, 0, EXEMPTION)),
                18000, 0, 18000, 0, 18000, "RE202700001", LocalDate.of(2027, 2, 1));

        var result = service.embed(pdfA1, document);
        var xml = new String(result.xml(), StandardCharsets.UTF_8);

        assertThat(xml).contains("<ram:TypeCode>381</ram:TypeCode>")
                .contains("RE202700001");
        assertValid(result);
    }

    @Test
    void differingTotalsStopTheInvoiceWithoutNamingValues() {
        var lines = List.of(new Line(1, "Beratung", 1000, "HUR", 9000, 0, "E", 0, 9000));
        var document = invoice(Type.INVOICE, lines, List.of(new TaxGroup("E", 0, 9000, 0, 0, EXEMPTION)),
                9000, 0, 9000, 0, 9001);

        assertThatThrownBy(() -> service.embed(pdfA1, document))
                .isInstanceOf(EInvoiceException.class)
                .hasMessageContaining("GrandTotalAmount")
                .hasMessageNotContaining("90");
    }

    @Test
    void validatorErrorsAreReportedByRuleIdOnly() {
        var lines = List.of(new Line(1, "Beratung", 1000, "HUR", 9000, 0, "E", 0, 9000));
        var withoutTaxIds = new Party("Beispiel Nebengewerbe", null, "Musterweg 1", "12345", "Beispielstadt", "DE",
                null, null, null, null);
        var document = new EInvoiceDocument(Type.INVOICE, "RE202700003", LocalDate.of(2027, 2, 1), null, null,
                LocalDate.of(2027, 2, 15), withoutTaxIds, buyer(), bank(), lines,
                List.of(new TaxGroup("E", 0, 9000, 0, 0, EXEMPTION)), 9000, 0, 9000, 0, 9000, null, null);

        assertThatThrownBy(() -> service.embed(pdfA1, document))
                .isInstanceOf(EInvoiceException.class)
                .satisfies(e -> assertThat(((EInvoiceException) e).rules()).isNotEmpty());
    }

    @Test
    void smallBusinessWithTotalDiscountAndShippingChargeIsValid() throws IOException {
        var lines = List.of(
                new Line(1, "Webentwicklung", 6000, "HUR", 8500, 1000, "E", 0, 45900),
                new Line(2, "Domain-Registrierung", 1000, "H87", 1200, 0, "E", 0, 1200),
                new Line(3, "Anfahrt", 1000, "LS", 4000, 0, "E", 0, 4000));
        var document = withCharge(invoice(Type.INVOICE, lines,
                List.of(new TaxGroup("E", 0, 49040, 0, 2555, EXEMPTION)), 51100, 2555, 49040, 0, 49040),
                "PO-4711", new Charge("Versand", 495, "E", 0));

        var result = service.embed(pdfA1, document);
        var xml = new String(result.xml(), StandardCharsets.UTF_8);

        assertThat(xml).contains("<ram:ChargeTotalAmount>4.95</ram:ChargeTotalAmount>")
                .contains("<ram:GrandTotalAmount>490.40</ram:GrandTotalAmount>")
                .contains("<ram:BuyerReference>PO-4711</ram:BuyerReference>")
                .contains("<ram:Reason>Versand</ram:Reason>");
        assertValid(result);
    }

    @Test
    void smallBusinessShippingChargeWithoutDiscountIsValid() throws IOException {
        var lines = List.of(new Line(1, "Beratung", 1000, "HUR", 9000, 0, "E", 0, 9000));
        var document = withCharge(invoice(Type.INVOICE, lines,
                List.of(new TaxGroup("E", 0, 9495, 0, 0, EXEMPTION)), 9000, 0, 9495, 0, 9495),
                null, new Charge("Versand", 495, "E", 0));

        assertValid(service.embed(pdfA1, document));
    }

    @Test
    void shippingChargeInTheTaxGroupOfAnItemWithTotalDiscountIsValid() throws IOException {
        var lines = List.of(
                new Line(1, "Beratung", 1000, "DAY", 100000, 0, "S", 1900, 100000),
                new Line(2, "Schulungsunterlagen", 1000, "H87", 10000, 0, "S", 700, 10000));
        var groups = List.of(
                new TaxGroup("S", 1900, 91000, 17290, 10000, null),
                new TaxGroup("S", 700, 9000, 630, 1000, null));
        var document = withCharge(invoice(Type.INVOICE, lines, groups, 110000, 11000, 100000, 17920, 117920),
                null, new Charge("Versand", 1000, "S", 1900));

        var result = service.embed(pdfA1, document);

        assertThat(new String(result.xml(), StandardCharsets.UTF_8))
                .contains("<ram:ChargeTotalAmount>10.00</ram:ChargeTotalAmount>")
                .contains("<ram:GrandTotalAmount>1179.20</ram:GrandTotalAmount>");
        assertValid(result);
    }

    @Test
    void shippingChargeWithItsOwnRateFormsItsOwnTaxGroup() throws IOException {
        var lines = List.of(new Line(1, "Beratung", 1000, "DAY", 100000, 0, "S", 1900, 100000));
        var groups = List.of(
                new TaxGroup("S", 1900, 100000, 19000, 0, null),
                new TaxGroup("S", 700, 500, 35, 0, null));
        var document = withCharge(invoice(Type.INVOICE, lines, groups, 100000, 0, 100500, 19035, 119535),
                null, new Charge("Versand", 500, "S", 700));

        assertValid(service.embed(pdfA1, document));
    }

    @Test
    void creditNoteCarriesTheShippingChargePositive() throws IOException {
        var lines = List.of(new Line(1, "Beratung", 2000, "HUR", 9000, 0, "E", 0, 18000));
        var document = new EInvoiceDocument(Type.CREDIT_NOTE, "ST202700001", LocalDate.of(2027, 2, 3), null, null,
                null, seller(), buyer(), bank(), lines, List.of(new TaxGroup("E", 0, 18495, 0, 0, EXEMPTION)),
                18000, 0, 18495, 0, 18495, "RE202700001", LocalDate.of(2027, 2, 1), "PO-4711",
                new Charge("Versand", 495, "E", 0));

        var result = service.embed(pdfA1, document);

        assertThat(new String(result.xml(), StandardCharsets.UTF_8)).contains("<ram:TypeCode>381</ram:TypeCode>")
                .contains("<ram:ChargeTotalAmount>4.95</ram:ChargeTotalAmount>");
        assertValid(result);
    }

    private static EInvoiceDocument withCharge(EInvoiceDocument base, String buyerReference, Charge charge) {
        return new EInvoiceDocument(base.type(), base.number(), base.issueDate(), base.serviceFrom(),
                base.serviceTo(), base.dueDate(), base.seller(), base.buyer(), base.bank(), base.lines(),
                base.taxGroups(), base.lineTotalCents(), base.allowanceCents(), base.netCents(), base.taxCents(),
                base.grossCents(), base.referencedNumber(), base.referencedDate(), buyerReference, charge);
    }

    private static EInvoiceDocument invoice(Type type, List<Line> lines, List<TaxGroup> groups, long lineTotal,
            long allowance, long net, long tax, long gross) {
        return new EInvoiceDocument(type, "RE202700001", LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 1, 29), seller(), buyer(), bank(), lines, groups,
                lineTotal, allowance, net, tax, gross, null, null);
    }

    private static Party seller() {
        return new Party("Beispiel Nebengewerbe", "Erika Beispiel", "Musterweg 1", "12345", "Beispielstadt", "DE",
                "info@example.test", "+49 30 1234567", "123/456/78901", "DE123456789");
    }

    private static Party buyer() {
        return new Party("Muster Verein e.V.", "Max Mustermann", "Teststraße 12", "54321", "Musterhausen", "DE",
                "max@example.test", null, null, "DE987654321");
    }

    private static Bank bank() {
        return new Bank("Erika Beispiel", "DE02120300000000202051", "BYLADEM1001");
    }

    private static void assertValid(EInvoiceResult result) {
        var report = new ZUGFeRDValidator().validate(result.pdf(), "invoice.pdf");

        assertThat(report)
                .doesNotContain("<error")
                .doesNotContain("<fatal")
                .doesNotContain("status=\"invalid\"");
    }

    private static void assertEmbedded(EInvoiceResult result) throws IOException {
        try (var document = Loader.loadPDF(result.pdf())) {
            var names = document.getDocumentCatalog()
                    .getNames()
                    .getEmbeddedFiles()
                    .getNames();

            assertThat(names).containsKey("factur-x.xml");
        }
    }
}
