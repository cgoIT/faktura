package de.cgoit.faktura.render;

import static de.cgoit.faktura.render.RenderSupport.date;
import static de.cgoit.faktura.render.RenderSupport.dataUri;
import static de.cgoit.faktura.render.RenderSupport.format;

import de.cgoit.faktura.render.internal.RenderProperties;
import de.cgoit.faktura.shared.Iban;
import de.cgoit.faktura.shared.MoneyFormatter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Renders an invoice, cancellation or offer to HTML and to a PDF/A-1b document with embedded fonts. The layout follows
 * DIN 5008 (address window, return address line); the footer with the sender details and the page counter
 * repeat on every page. The renderer prints exactly what the {@link RenderModel} contains.
 */
@Service
public class InvoiceRenderer {

    private final RenderSupport support;
    private final GirocodeService girocode;
    private final MoneyFormatter money;

    public InvoiceRenderer(MessageSource messages, RenderProperties properties, GirocodeService girocode,
            MoneyFormatter money) {
        this.support = new RenderSupport(messages, properties);
        this.girocode = girocode;
        this.money = money;
    }

    /**
     * Renders the model to the XHTML that is turned into the PDF.
     */
    public String renderHtml(RenderModel model) {
        var context = support.letterContext(model.sender());
        var cancellation = model.kind() == RenderModel.Kind.CANCELLATION;

        context.setVariable("model", model);
        context.setVariable("money", money);
        context.setVariable("countryName", support.countryName(model.recipient(), model.sender()));
        context.setVariable("items", model.items().stream()
                .map(ItemView::of)
                .toList());
        context.setVariable("ibanDisplay", model.account() == null ? null : Iban.format(model.account().iban()));
        context.setVariable("headerHtml", RichText.toXhtml(model.headerText()));
        context.setVariable("footerHtml", RichText.toXhtml(model.footerText()));
        context.setVariable("showTax", !model.taxLines().isEmpty());
        context.setVariable("showSubtotal", model.discountCents() != 0 || model.shippingCents() != 0);

        if (cancellation && model.referencedNumber() != null) {
            context.setVariable("cancellationText", format(support.properties().cancellationText(),
                    model.referencedNumber(), date(model.referencedDate())));
        }

        var payable = !cancellation
                && model.kind() != RenderModel.Kind.OFFER
                && model.account() != null
                && model.account().iban() != null
                && model.grossCents() > 0;

        if (payable) {
            var image = model.number() != null
                    ? girocode.png(model.account(), model.grossCents(), model.number())
                    : girocode.placeholderPng();

            context.setVariable("girocode", dataUri("image/png", image));
        }

        return support.process("invoice", context);
    }

    /**
     * Renders the model to a PDF/A-1b document.
     */
    public byte[] renderPdf(RenderModel model) {
        return support.pdf(renderHtml(model));
    }

    /**
     * An item with its description split into the first line (title) and the rest (text).
     */
    public record ItemView(int position, String title, String text, long quantityThousandths, String unitName,
            long unitPriceCents, int discountBp, int taxRateBp, long lineTotalCents) {

        private static final long ONE_THOUSANDTHS = 1000;

        static ItemView of(RenderModel.Item item) {
            var lines = item.description().strip().split("\\R", 2);
            var text = lines.length > 1 && !lines[1].isBlank() ? lines[1].strip() : null;

            return new ItemView(item.position(), lines[0].strip(), text, item.quantityThousandths(), unitLabel(item),
                    item.unitPriceCents(), item.discountBp(), item.taxRateBp(), item.lineTotalCents());
        }

        /**
         * Singular name for a quantity of exactly 1, plural name otherwise; without a plural the name is used.
         */
        private static String unitLabel(RenderModel.Item item) {
            var plural = item.quantityThousandths() != ONE_THOUSANDTHS
                    && item.unitNamePlural() != null
                    && !item.unitNamePlural().isBlank();

            return plural ? item.unitNamePlural() : item.unitName();
        }

        /**
         * Hides the free text from logs.
         */
        @Override
        public String toString() {
            return "ItemView[position=" + position + "]";
        }
    }
}
