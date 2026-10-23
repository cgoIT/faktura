package de.cgoit.faktura.export.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import de.cgoit.faktura.document.InvoiceExportRow;
import de.cgoit.faktura.document.PaymentExportRow;
import de.cgoit.faktura.document.TaxTotal;
import de.cgoit.faktura.shared.MoneyFormatter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Builds the two CSV files of the accounting export from the stored invoice and payment values. Headings and
 * names of types and states come from the message source.
 */
@Component
public class AccountingCsvExporter {

    private static final Locale LOCALE = Locale.GERMAN;
    private static final String CURRENCY = "EUR";

    private final MessageSource messages;
    private final MoneyFormatter money;

    public AccountingCsvExporter(MessageSource messages, MoneyFormatter money) {
        this.messages = messages;
        this.money = money;
    }

    /**
     * Writes the invoice file: one column per tax rate above zero that occurs in the rows, in ascending order.
     * Amounts are the stored ones, a cancellation is negative.
     */
    public byte[] invoices(List<InvoiceExportRow> rows) {
        var rates = new TreeSet<Integer>();

        for (var row : rows) {
            for (var total : row.taxTotals()) {
                if (total.rateBp() > 0) {
                    rates.add(total.rateBp());
                }
            }
        }

        var csv = new StringBuilder(CsvWriter.BOM);
        var heading = new ArrayList<>(List.of(text("export.col.number"), text("export.col.type"),
                text("export.col.issueDate"), text("export.col.serviceFrom"), text("export.col.serviceTo"),
                text("export.col.customerNumber"), text("export.col.customerName"),
                text("export.col.customerReference"), text("export.col.net"), text("export.col.shipping")));

        for (var rate : rates) {
            heading.add(CsvWriter.text(message("export.col.tax", money.percent(rate))));
        }

        heading.addAll(List.of(text("export.col.gross"), text("export.col.currency"), text("export.col.state"),
                text("export.col.paidOn")));
        csv.append(CsvWriter.row(heading));

        for (var row : rows) {
            var cells = new ArrayList<>(List.of(CsvWriter.text(row.number()),
                    CsvWriter.text(message("export.type." + row.type().name())), CsvWriter.date(row.issueDate()),
                    CsvWriter.date(row.serviceFrom()), CsvWriter.date(row.serviceTo()),
                    CsvWriter.text(row.customerNumber()), CsvWriter.text(row.customerName()),
                    CsvWriter.text(row.customerReference()), CsvWriter.amount(row.netCents()),
                    CsvWriter.amount(row.shippingCents())));

            for (var rate : rates) {
                cells.add(CsvWriter.amount(row.taxTotals().stream()
                        .filter(total -> total.rateBp() == rate)
                        .mapToLong(TaxTotal::taxCents)
                        .sum()));
            }

            cells.add(CsvWriter.amount(row.grossCents()));
            cells.add(CURRENCY);
            cells.add(row.state() == null ? "" : CsvWriter.text(message("export.state." + row.state().name())));
            cells.add(CsvWriter.date(row.paidOn()));
            csv.append(CsvWriter.row(cells));
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the payment file, listing the inflow by payment date. A reversing entry has a negative amount.
     */
    public byte[] payments(List<PaymentExportRow> rows) {
        var csv = new StringBuilder(CsvWriter.BOM);

        csv.append(CsvWriter.row(List.of(text("export.col.paymentDate"), text("export.col.invoiceNumber"),
                text("export.col.amount"), text("export.col.method"), text("export.col.reference"),
                text("export.col.reversal"))));

        for (var row : rows) {
            csv.append(CsvWriter.row(List.of(CsvWriter.date(row.date()), CsvWriter.text(row.invoiceNumber()),
                    CsvWriter.amount(row.amountCents()),
                    CsvWriter.text(message("export.method." + row.method().name())),
                    CsvWriter.text(row.reference()),
                    CsvWriter.text(message(row.reversal() ? "export.yes" : "export.no")))));
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String text(String key) {
        return CsvWriter.text(message(key));
    }

    private String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, LOCALE);
    }
}
