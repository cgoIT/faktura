package de.cgoit.faktura.export.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceExportRow;
import de.cgoit.faktura.document.PaymentExportRow;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentState;
import de.cgoit.faktura.document.TaxTotal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SqliteTest
class AccountingCsvExporterTest {

    @Autowired
    private AccountingCsvExporter exporter;

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void invoiceFileHasOneTaxColumnPerOccurringRate() {
        var regular = new InvoiceExportRow("RE202700001", DocumentType.INVOICE, LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), "K-00001", "Muster GmbH", "PO 7", 15_000, 500,
                17_790, List.of(new TaxTotal("S", 1900, 10_000, 1_900), new TaxTotal("S", 700, 5_000, 350),
                        new TaxTotal("S", 700, 100, 7)),
                PaymentState.PAID, LocalDate.of(2027, 2, 20));
        var cancellation = new InvoiceExportRow("ST202700001", DocumentType.CANCELLATION, LocalDate.of(2027, 3, 1),
                null, null, "K-00001", "Muster GmbH", null, -15_000, -500, -17_790,
                List.of(new TaxTotal("S", 1900, -10_000, -1_900)), null, null);

        var lines = text(exporter.invoices(List.of(regular, cancellation))).split("\r\n");

        assertThat(lines[0]).startsWith("﻿Nummer;Typ;Rechnungsdatum;Leistung von;Leistung bis;Kundennummer;")
                .endsWith(";Netto;Versandkosten (netto);Steuer 7 %;Steuer 19 %;Brutto;Währung;Status;Bezahlt am");
        assertThat(lines[1]).isEqualTo("RE202700001;Rechnung;2027-02-01;2027-01-01;2027-01-31;K-00001;Muster GmbH;"
                + "PO 7;150,00;5,00;3,57;19,00;177,90;EUR;Bezahlt;2027-02-20");
        assertThat(lines[2]).isEqualTo("ST202700001;Storno;2027-03-01;;;K-00001;Muster GmbH;;-150,00;-5,00;0,00;"
                + "-19,00;-177,90;EUR;;");
    }

    @Test
    void smallBusinessFileHasNoTaxColumn() {
        var row = new InvoiceExportRow("RE202700002", DocumentType.INVOICE, LocalDate.of(2027, 2, 1), null, null,
                "K-00002", "Beispiel", null, 48_545, 0, 48_545, List.of(new TaxTotal("E", 0, 48_545, 0)),
                PaymentState.OPEN, null);

        var lines = text(exporter.invoices(List.of(row))).split("\r\n");

        assertThat(lines[0]).doesNotContain("Steuer");
        assertThat(lines[1]).endsWith(";485,45;EUR;Offen;");
    }

    @Test
    void customerTextIsQuotedAndDefusedInTheFile() {
        var row = new InvoiceExportRow("RE202700003", DocumentType.INVOICE, LocalDate.of(2027, 2, 1), null, null,
                "K-1", "=cmd|' /C calc'!A0", "A;B", 100, 0, 100, List.of(), PaymentState.CANCELLED, null);

        var line = text(exporter.invoices(List.of(row))).split("\r\n")[1];

        assertThat(line).contains(";'=cmd|' /C calc'!A0;\"A;B\";").endsWith("Storniert;");
    }

    @Test
    void paymentFileListsReversalsAsNegativeAmounts() {
        var rows = List.of(
                new PaymentExportRow(LocalDate.of(2027, 2, 20), "RE202700001", 40_000, PaymentMethod.BANK_TRANSFER,
                        "Rechnung 1", false),
                new PaymentExportRow(LocalDate.of(2027, 2, 21), "RE202700001", -40_000, PaymentMethod.BANK_TRANSFER,
                        "+Storno", true));

        var lines = text(exporter.payments(rows)).split("\r\n");

        assertThat(lines[0]).isEqualTo("﻿Zahlungsdatum;Rechnungsnummer;Betrag;Art;Verwendungszweck;Gegenbuchung");
        assertThat(lines[1]).isEqualTo("2027-02-20;RE202700001;400,00;Überweisung;Rechnung 1;nein");
        assertThat(lines[2]).isEqualTo("2027-02-21;RE202700001;-400,00;Überweisung;'+Storno;ja");
    }

    @Test
    void emptyYearGivesOnlyTheHeading() {
        assertThat(text(exporter.invoices(List.of())).split("\r\n")).hasSize(1);
        assertThat(text(exporter.payments(List.of())).split("\r\n")).hasSize(1);
    }
}
