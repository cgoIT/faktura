package de.cgoit.faktura.document.internal;

import java.util.List;

import de.cgoit.faktura.document.Calculation;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceItemInfo;
import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.render.RenderModel;
import de.cgoit.faktura.render.RenderModel.Account;
import de.cgoit.faktura.render.RenderModel.Item;
import de.cgoit.faktura.render.RenderModel.Kind;
import de.cgoit.faktura.render.RenderModel.Party;
import de.cgoit.faktura.render.RenderModel.TaxLine;
import org.springframework.stereotype.Component;

/**
 * Builds the model the renderer prints. It reads only the snapshots and values handed in, never live master data, so
 * a finalized document always prints the same.
 */
@Component
public class RenderModelFactory {

    /**
     * Builds the model of an invoice, cancellation or offer.
     *
     * @param number     document number, {@code null} for a draft preview
     * @param referenced the cancelled invoice of a cancellation, {@code null} otherwise
     * @param headerText printed header text with all placeholders filled in
     * @param footerText printed footer text with all placeholders filled in
     */
    public RenderModel build(Header header, String number, boolean draft, List<InvoiceItemInfo> items,
            Calculation calculation, SenderSnapshot sender, RecipientSnapshot recipient, TaxSnapshot tax,
            Header referenced, String headerText, String footerText) {
        var kind = switch (header.type()) {
            case CANCELLATION -> Kind.CANCELLATION;
            case OFFER -> Kind.OFFER;
            case INVOICE -> Kind.INVOICE;
        };
        var taxLines = calculation.taxTotals().stream()
                .filter(total -> total.rateBp() != 0)
                .map(total -> new TaxLine(total.rateBp(), total.basisCents(), total.taxCents()))
                .toList();
        var printedItems = items.stream()
                .map(item -> new Item(item.position(), item.description(), item.quantityThousandths(),
                        item.unitName(), item.unitNamePlural(), item.unitPriceCents(), item.discountBp(), item.taxRateBp(),
                        item.lineTotalCents()))
                .toList();

        return new RenderModel(kind, draft, number, header.issueDate(), header.serviceFrom(), header.serviceTo(),
                header.dueDate(), party(sender), party(recipient), recipient.number(),
                headerText, footerText, printedItems, calculation.subtotalCents(),
                calculation.discountCents(), calculation.netCents(), calculation.taxCents(),
                calculation.grossCents(), taxLines, tax.exemptionText(), tax.noticeText(), account(sender),
                referenced == null ? null : referenced.number(), referenced == null ? null : referenced.issueDate(),
                header.customerReference(), header.shipping().name(), calculation.shippingCents(),
                header.validUntil());
    }

    public static Party party(SenderSnapshot sender) {
        return new Party(sender.name(), sender.owner(), sender.street(), sender.zip(), sender.city(),
                sender.country(), sender.email(), sender.phone(), sender.taxNumber(), sender.vatId());
    }

    public static Party party(RecipientSnapshot recipient) {
        return new Party(recipient.name(), recipient.contactName(), recipient.street(), recipient.zip(), recipient.city(),
                recipient.country(), recipient.invoiceEmail(), null, null, recipient.vatId());
    }

    public static Account account(SenderSnapshot sender) {
        if (sender.iban() == null || sender.iban().isBlank()) {
            return null;
        }

        var holder = sender.owner() == null || sender.owner().isBlank() ? sender.name() : sender.owner();

        return new Account(holder, sender.bankName(), sender.iban(), sender.bic());
    }
}
