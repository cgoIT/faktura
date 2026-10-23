package de.cgoit.faktura.render;

import static de.cgoit.faktura.render.RenderSupport.dataUri;

import de.cgoit.faktura.render.internal.RenderProperties;
import de.cgoit.faktura.shared.Iban;
import de.cgoit.faktura.shared.MoneyFormatter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Renders a dunning letter to HTML and to a PDF/A-1b document. It shares letterhead, address window, footer and
 * fonts with the invoice (see {@code render/parts.xhtml}); a dunning letter is a plain PDF without e-invoice XML.
 */
@Service
public class DunningRenderer {

    private final RenderSupport support;
    private final GirocodeService girocode;
    private final MoneyFormatter money;

    public DunningRenderer(MessageSource messages, RenderProperties properties, GirocodeService girocode,
            MoneyFormatter money) {
        this.support = new RenderSupport(messages, properties);
        this.girocode = girocode;
        this.money = money;
    }

    /**
     * Renders the model to the XHTML that is turned into the PDF.
     */
    public String renderHtml(DunningRenderModel model) {
        var context = support.letterContext(model.sender());

        context.setVariable("model", model);
        context.setVariable("money", money);
        context.setVariable("countryName", support.countryName(model.recipient(), model.sender()));
        context.setVariable("ibanDisplay", model.account() == null ? null : Iban.format(model.account().iban()));
        context.setVariable("headerHtml", RichText.toXhtml(model.headerText()));
        context.setVariable("footerHtml", RichText.toXhtml(model.footerText()));

        var payable = model.account() != null
                && model.account().iban() != null
                && model.totalCents() > 0;

        if (payable) {
            var image = model.number() != null
                    ? girocode.png(model.account(), model.totalCents(), model.invoiceNumber())
                    : girocode.placeholderPng();

            context.setVariable("girocode", dataUri("image/png", image));
        }

        return support.process("dunning", context);
    }

    /**
     * Renders the model to a PDF/A-1b document.
     */
    public byte[] renderPdf(DunningRenderModel model) {
        return support.pdf(renderHtml(model));
    }
}
