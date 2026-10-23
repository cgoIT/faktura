package de.cgoit.faktura.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DunningRendererTest {

    private final DunningRenderer renderer = RenderFixtures.dunningRenderer();

    @Test
    void letterShowsTheClaimTheFooterAndEmbedsTheFonts() throws IOException {
        var pdf = renderer.renderPdf(RenderFixtures.dunning(false));

        try (var document = Loader.loadPDF(pdf)) {
            var text = new PDFTextStripper().getText(document);

            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(text).contains("1. Mahnung", "MA202700001", "RE202700001", "Bereits gezahlt", "75,00", "5,00",
                    "1,23", "81,23", "08.03.2027", RenderFixtures.FOOTER_NAME, "Mustermann");
            assertThat(text).doesNotContain("ENTWURF");

            for (var font : document.getPage(0).getResources().getFontNames()) {
                assertThat(document.getPage(0).getResources().getFont(font).isEmbedded()).isTrue();
            }

            assertThat(document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes())
                    .asString().contains("pdfaid:part");
        }
    }

    @Test
    void zeroFeeAndInterestAreNotPrintedAndPreviewHasWatermark() throws IOException {
        var model = RenderFixtures.dunning(true);
        var plain = new DunningRenderModel(true, null, model.date(), model.levelName(), model.sender(), model.recipient(),
                model.customerNumber(), model.invoiceNumber(), model.invoiceDate(), model.invoiceDueDate(), 10_000, 0,
                10_000, 0, 0, 10_000, model.newDueDate(), model.headerText(), model.footerText(), model.account());

        try (var document = Loader.loadPDF(renderer.renderPdf(plain))) {
            var text = new PDFTextStripper().getText(document);

            assertThat(text).contains("ENTWURF").doesNotContain("Mahngebühr").doesNotContain("Verzugszinsen")
                    .doesNotContain("Bereits gezahlt");
        }
    }
}
