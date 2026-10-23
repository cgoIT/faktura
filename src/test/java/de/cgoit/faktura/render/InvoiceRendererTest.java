package de.cgoit.faktura.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class InvoiceRendererTest {

    private static final Pattern PAGE_OF = Pattern.compile("Seite (\\d+) von (\\d+)");

    private final InvoiceRenderer renderer = RenderFixtures.renderer();

    @Test
    void shortInvoiceIsOnePageWithFooterAndPageCounter() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.invoice(2));

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(textOf(document, 1)).contains("Seite 1 von 1");
            assertFooter(document, 1);
        }
    }

    @Test
    void footerAndPageCounterRepeatOnEveryPageOfALongInvoice() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.invoice(40));
        write("many-items.pdf", pdf);

        try (var document = Loader.loadPDF(pdf)) {
            var pages = document.getNumberOfPages();

            assertThat(pages).isGreaterThanOrEqualTo(2);

            for (var page = 1; page <= pages; page++) {
                var matcher = PAGE_OF.matcher(textOf(document, page));

                assertThat(matcher.find()).as("page counter on page " + page).isTrue();
                assertThat(Integer.parseInt(matcher.group(1))).isEqualTo(page);
                assertThat(Integer.parseInt(matcher.group(2))).isEqualTo(pages);
                assertFooter(document, page);
            }
        }
    }

    @Test
    void positionsNeverOverlapTheFooter() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.invoice(40));

        try (var document = Loader.loadPDF(pdf)) {
            for (var page = 1; page <= document.getNumberOfPages(); page++) {
                var footerTop = lineTop(document, page, RenderFixtures.FOOTER_NAME + "");
                var lowestBody = lowestBodyLine(document, page);

                assertThat(lowestBody).as("lowest body text on page " + page).isLessThan(footerTop);
            }
        }
    }

    @Test
    void fontsAreEmbeddedAndTheDocumentIsMarkedPdfA() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.invoice(2));

        try (var document = Loader.loadPDF(pdf)) {
            var resources = document.getPage(0).getResources();

            for (var name : resources.getFontNames()) {
                assertThat(resources.getFont(name).isEmbedded()).as(name.getName()).isTrue();
            }
        }

        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("pdfaid:part");
    }

    @Test
    void customerReferenceAndShippingArePrintedAfterTheDiscount() throws IOException {
        var base = RenderFixtures.invoice(2);
        var model = new RenderModel(base.kind(), false, base.number(), base.issueDate(), base.serviceFrom(),
                base.serviceTo(), base.dueDate(), base.sender(), base.recipient(), base.customerNumber(),
                base.headerText(), base.footerText(), base.items(), base.subtotalCents(), base.discountCents(),
                base.netCents() + 495, base.taxCents(), base.grossCents() + 495, base.taxLines(),
                base.exemptionText(), base.noticeText(), base.account(), null, null, "PO-4711", "Päckchen", 495);

        try (var document = Loader.loadPDF(renderer.renderPdf(model))) {
            var text = textOf(document, 1);

            assertThat(text).contains("Ihre Referenz:", "PO-4711", "Päckchen", "4,95 €");
        }
    }

    @Test
    void withoutReferenceAndShippingNeitherLineIsPrinted() throws IOException {
        try (var document = Loader.loadPDF(renderer.renderPdf(RenderFixtures.invoice(2)))) {
            assertThat(textOf(document, 1)).doesNotContain("Ihre Referenz").doesNotContain("Versandkosten");
        }
    }

    @Test
    void draftIsMarkedHasNoNumberAndShowsAPlaceholderInsteadOfTheGirocode() throws IOException {
        var base = RenderFixtures.invoice(2);
        var draft = new RenderModel(base.kind(), true, null, base.issueDate(), base.serviceFrom(), base.serviceTo(),
                base.dueDate(), base.sender(), base.recipient(), base.customerNumber(),
                base.headerText(), base.footerText(), base.items(), base.subtotalCents(), base.discountCents(),
                base.netCents(), base.taxCents(), base.grossCents(), base.taxLines(), base.exemptionText(),
                base.noticeText(), base.account(), null, null);

        var html = renderer.renderHtml(draft);
        write("draft.pdf", renderer.renderPdf(draft));

        assertThat(html).contains("ENTWURF").contains("Girocode");
    }

    @Test
    void regularInvoicePrintsTaxRatePerItemAndTaxBlock() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.regularInvoice());
        write("regular.pdf", pdf);

        try (var document = Loader.loadPDF(pdf)) {
            var text = textOf(document, 1);

            assertThat(text).contains("USt.", "19 %", "Umsatzsteuer 19 %", "Netto", "Rechnungsbetrag (brutto)",
                    "11,90 €");
        }
    }

    @Test
    void smallBusinessInvoicePrintsNoTaxColumnOrBlock() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.invoice(2));

        try (var document = Loader.loadPDF(pdf)) {
            assertThat(textOf(document, 1)).doesNotContain("USt.", "Netto", "(brutto)");
        }
    }

    @Test
    void unitNameIsSingularForExactlyOneAndPluralOtherwise() {
        var base = RenderFixtures.invoice(1);

        assertThat(renderer.renderHtml(withQuantity(base, 1000, "Stunde", "Stunden")))
                .contains("1 Stunde<").doesNotContain("Stunden<");
        assertThat(renderer.renderHtml(withQuantity(base, 2000, "Stunde", "Stunden"))).contains("2 Stunden<");
        assertThat(renderer.renderHtml(withQuantity(base, 1500, "Stunde", "Stunden"))).contains("1,5 Stunden<");
        assertThat(renderer.renderHtml(withQuantity(base, 2000, "Tag", null))).contains("2 Tag<");
    }

    private static RenderModel withQuantity(RenderModel base, long thousandths, String unit, String plural) {
        var item = new RenderModel.Item(1, "Arbeit", thousandths, unit, plural, 1000, 0, 0, 1000);

        return new RenderModel(base.kind(), base.draft(), base.number(), base.issueDate(), base.serviceFrom(),
                base.serviceTo(), base.dueDate(), base.sender(), base.recipient(), base.customerNumber(),
                base.headerText(), base.footerText(), List.of(item), base.subtotalCents(),
                base.discountCents(), base.netCents(), base.taxCents(), base.grossCents(), base.taxLines(),
                base.exemptionText(), base.noticeText(), base.account(), null, null);
    }

    @Test
    void formattedTextsRenderAsPdfWithEmbeddedItalicAndBoldFonts() throws IOException {
        var base = RenderFixtures.invoice(2);
        var formatted = new RenderModel(base.kind(), false, base.number(), base.issueDate(), base.serviceFrom(),
                base.serviceTo(), base.dueDate(), base.sender(), base.recipient(), base.customerNumber(),
                "Das ist **fett**, *kursiv* und __unterstrichen__.\n\n- Punkt eins\n  - Unterpunkt\n- Punkt zwei\n"
                        + "\n    eingerückter Absatz",
                "1. erstens\n2. zweitens\n\n**Fett *und kursiv***", base.items(), base.subtotalCents(),
                base.discountCents(), base.netCents(), base.taxCents(), base.grossCents(), base.taxLines(),
                base.exemptionText(), base.noticeText(), base.account(), null, null);

        var pdf = renderer.renderPdf(formatted);
        write("formatted.pdf", pdf);

        try (var document = Loader.loadPDF(pdf)) {
            var text = textOf(document, 1);
            var fonts = new java.util.HashSet<String>();

            document.getPage(0).getResources().getFontNames().forEach(name -> {
                try {
                    var font = document.getPage(0).getResources().getFont(name);

                    assertThat(font.isEmbedded()).isTrue();
                    fonts.add(font.getName());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            assertThat(text).contains("Das ist", "fett", "kursiv", "unterstrichen", "Punkt eins", "Unterpunkt", "erstens")
                    .doesNotContain("**").doesNotContain("__");
            assertThat(fonts).anyMatch(name -> name.contains("Oblique")).anyMatch(name -> name.contains("Bold"));
        }
    }

    @Test
    void invoicePrintsExemptionTextFooterTextAndGirocodeWithoutBuiltInPaymentText() {
        var html = renderer.renderHtml(RenderFixtures.invoice(2));

        assertThat(html)
                .contains("Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.")
                .contains("Ich danke für die gute Zusammenarbeit.")
                .contains("Girocode");
        assertThat(html.split("Bitte überweisen", -1)).as("payment text only from the footer text").hasSize(2);
        assertThat(html.split("Zahlbar innerhalb", -1)).hasSize(2);
    }

    @Test
    void cancellationAndInvoicesWithoutAccountHaveNoGirocode() {
        var base = RenderFixtures.invoice(1);
        var withoutAccount = new RenderModel(base.kind(), false, base.number(), base.issueDate(), base.serviceFrom(),
                base.serviceTo(), base.dueDate(), base.sender(), base.recipient(), base.customerNumber(),
                base.headerText(), base.footerText(), base.items(), base.subtotalCents(), base.discountCents(),
                base.netCents(), base.taxCents(), base.grossCents(), base.taxLines(), base.exemptionText(),
                base.noticeText(), null, null, null);

        assertThat(renderer.renderHtml(withoutAccount)).doesNotContain("Girocode");
    }

    private static void assertFooter(PDDocument document, int page) throws IOException {
        var text = textOf(document, page);

        assertThat(text).as("footer on page " + page)
                .contains(RenderFixtures.FOOTER_NAME)
                .contains("E-Mail: info@example.test")
                .contains("IBAN: DE02 1203 0000 0000 2020 51");
    }

    private static String textOf(PDDocument document, int page) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);

        return stripper.getText(document);
    }

    /**
     * Top (y from the page top, in points) of the lowest line that starts with the text, i.e. the footer name.
     */
    private static float lineTop(PDDocument document, int page, String start) throws IOException {
        var tops = new ArrayList<Float>();
        var stripper = new PositionStripper(page, (text, y) -> {
            if (text.startsWith(start)) {
                tops.add(y);
            }
        });
        stripper.getText(document);

        return tops.stream().max(Float::compare).orElseThrow();
    }

    private static float lowestBodyLine(PDDocument document, int page) throws IOException {
        var ys = new ArrayList<Float>();
        var markers = List.of("Position ", "Beschreibung zu", "Umlauten", "Rechnungsbetrag", "Ich danke", "Gemäß", "Bitte",
                "Zahlbar", "Sofern");
        var stripper = new PositionStripper(page, (text, y) -> {
            if (markers.stream().anyMatch(text::contains)) {
                ys.add(y);
            }
        });
        stripper.getText(document);

        return ys.stream().max(Float::compare).orElse(0f);
    }

    private static void write(String name, byte[] content) throws IOException {
        Files.createDirectories(Path.of("target/render-samples"));
        Files.write(Path.of("target/render-samples").resolve(name), content);
    }

    private static final class PositionStripper extends PDFTextStripper {

        private final java.util.function.BiConsumer<String, Float> consumer;

        PositionStripper(int page, java.util.function.BiConsumer<String, Float> consumer) throws IOException {
            this.consumer = consumer;
            setStartPage(page);
            setEndPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            consumer.accept(text, positions.get(0).getYDirAdj());
        }
    }
}
