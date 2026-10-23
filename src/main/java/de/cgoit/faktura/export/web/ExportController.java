package de.cgoit.faktura.export.web;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import de.cgoit.faktura.document.AccountingExportService;
import de.cgoit.faktura.export.internal.AccountingCsvExporter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Page and downloads of the accounting export. Read-only: every route is a GET and nothing is logged.
 */
@Controller
@RequestMapping("/export")
public class ExportController {

    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 2100;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final AccountingExportService source;
    private final AccountingCsvExporter exporter;

    public ExportController(AccountingExportService source, AccountingCsvExporter exporter) {
        this.source = source;
        this.exporter = exporter;
    }

    @GetMapping
    public String show(@RequestParam(required = false) Integer year, Model model) {
        var years = source.years();
        var selected = year != null && years.contains(year) ? year : years.getFirst();

        model.addAttribute("years", years);
        model.addAttribute("year", selected);

        return "export/index";
    }

    @GetMapping("/invoices.csv")
    public ResponseEntity<byte[]> invoices(@RequestParam int year) {
        return download("invoices-" + checked(year) + ".csv", () -> exporter.invoices(source.invoices(year)));
    }

    @GetMapping("/payments.csv")
    public ResponseEntity<byte[]> payments(@RequestParam int year) {
        return download("payments-" + checked(year) + ".csv", () -> exporter.payments(source.payments(year)));
    }

    private static int checked(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new ResponseStatusException(BAD_REQUEST);
        }

        return year;
    }

    private static ResponseEntity<byte[]> download(String filename, Supplier<byte[]> content) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename)
                        .build()
                        .toString())
                .body(content.get());
    }
}
