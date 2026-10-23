package de.cgoit.faktura.einvoice.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.cgoit.faktura.einvoice.EInvoiceDocument;
import de.cgoit.faktura.einvoice.EInvoiceException;
import org.w3c.dom.Element;

/**
 * Compares the amounts in the generated XML with the amounts of the document. Mustang calculates the sums itself;
 * the stored amounts are the ones that count, so any difference stops the invoice.
 */
final class TotalsCheck {

    private TotalsCheck() {
    }

    /**
     * Verifies line totals, tax breakdown and header sums.
     *
     * @throws EInvoiceException if one amount differs; the message names the field but never a value
     */
    static void verify(byte[] xml, EInvoiceDocument document) {
        var root = XmlReader.parse(xml)
                .getDocumentElement();
        var transaction = XmlReader.child(root, "SupplyChainTradeTransaction");
        var settlement = XmlReader.child(transaction, "ApplicableHeaderTradeSettlement");
        var summation = XmlReader.child(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");

        compareLines(transaction, document);
        compareTaxGroups(settlement, document);

        expect("LineTotalAmount", cents(summation, "LineTotalAmount"), document.lineTotalCents());
        expect("AllowanceTotalAmount", cents(summation, "AllowanceTotalAmount"), document.allowanceCents());
        expect("ChargeTotalAmount", cents(summation, "ChargeTotalAmount"),
                document.charge() == null ? 0 : document.charge().cents());
        expect("TaxBasisTotalAmount", cents(summation, "TaxBasisTotalAmount"), document.netCents());
        expect("TaxTotalAmount", cents(summation, "TaxTotalAmount"), document.taxCents());
        expect("GrandTotalAmount", cents(summation, "GrandTotalAmount"), document.grossCents());
        expect("DuePayableAmount", cents(summation, "DuePayableAmount"), document.grossCents());
    }

    private static void compareLines(Element transaction, EInvoiceDocument document) {
        var lines = XmlReader.children(transaction, "IncludedSupplyChainTradeLineItem");

        expect("line count", lines.size(), document.lines().size());

        for (var i = 0; i < lines.size(); i++) {
            var lineSettlement = XmlReader.child(lines.get(i), "SpecifiedLineTradeSettlement");
            var lineSummation = XmlReader.child(lineSettlement, "SpecifiedTradeSettlementLineMonetarySummation");

            expect("LineTotalAmount of position " + (i + 1), cents(lineSummation, "LineTotalAmount"),
                    document.lines().get(i).lineTotalCents());
        }
    }

    private static void compareTaxGroups(Element settlement, EInvoiceDocument document) {
        var expected = document.taxGroups().stream()
                .sorted(Comparator.comparing(EInvoiceDocument.TaxGroup::category)
                        .thenComparingInt(EInvoiceDocument.TaxGroup::rateBp))
                .toList();
        var actual = new ArrayList<Element>(XmlReader.children(settlement, "ApplicableTradeTax"));

        actual.sort(Comparator.comparing((Element tax) -> text(tax, "CategoryCode"))
                .thenComparingLong(tax -> rateBp(tax)));
        expect("tax group count", actual.size(), expected.size());

        for (var i = 0; i < actual.size(); i++) {
            var tax = actual.get(i);
            var group = expected.get(i);
            var label = "tax group " + group.category() + " " + group.rateBp();

            expect(label + " category", text(tax, "CategoryCode").equals(group.category()) ? 1 : 0, 1);
            expect(label + " rate", rateBp(tax), group.rateBp());
            expect(label + " basis", cents(tax, "BasisAmount"), group.basisCents());
            expect(label + " tax", cents(tax, "CalculatedAmount"), group.taxCents());
        }
    }

    private static long rateBp(Element tax) {
        var rate = text(tax, "RateApplicablePercent");

        return rate.isEmpty() ? 0 : new BigDecimal(rate).movePointRight(2).longValueExact();
    }

    private static long cents(Element parent, String name) {
        var text = text(parent, name);

        if (text.isEmpty()) {
            return 0;
        }

        return new BigDecimal(text).movePointRight(2).longValueExact();
    }

    private static String text(Element parent, String name) {
        var element = XmlReader.child(parent, name);

        return element == null ? "" : element.getTextContent().strip();
    }

    private static void expect(String field, long actual, long expected) {
        if (actual != expected) {
            throw new EInvoiceException("Amount in the XML differs from the document: " + field, List.of());
        }
    }
}
