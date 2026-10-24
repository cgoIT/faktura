package de.cgoit.faktura.document.internal;

import static de.cgoit.faktura.shared.Texts.blankToNull;
import static de.cgoit.faktura.shared.Texts.isBlank;

import java.util.List;

import de.cgoit.faktura.document.Calculation;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceItemInfo;
import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.einvoice.EInvoiceDocument;
import de.cgoit.faktura.einvoice.EInvoiceProperties;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Bank;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Charge;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Line;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Party;
import de.cgoit.faktura.einvoice.EInvoiceDocument.TaxGroup;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Type;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Component;

/**
 * Builds the data of the electronic invoice from the stored values of a document being finalized. Nothing is
 * recalculated: amounts, tax groups and the exemption text come from the calculation and the tax snapshot. A
 * cancellation stores negated quantities and amounts; the electronic credit note carries them positive again.
 */
@Component
public class EInvoiceMapper {

    private final EInvoiceProperties properties;

    public EInvoiceMapper(EInvoiceProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the e-invoice data of an invoice or cancellation.
     *
     * @param referenced the cancelled invoice of a cancellation, {@code null} otherwise
     * @throws RuleViolationException if the seller has neither tax number nor VAT id, or a category without tax
     *                                has no exemption text in the tax profile
     */
    public EInvoiceDocument map(Header header, String number, List<InvoiceItemInfo> items, Calculation calculation,
            SenderSnapshot sender, RecipientSnapshot recipient, TaxSnapshot tax, Header referenced) {
        if (isBlank(sender.taxNumber()) && isBlank(sender.vatId())) {
            throw new RuleViolationException("einvoice.error.sellerTaxId");
        }

        var credit = header.type() == DocumentType.CANCELLATION;
        var sign = credit ? -1 : 1;
        var lines = items.stream()
                .map(item -> line(item, sign))
                .toList();
        var shipping = header.shipping();
        var charge = shipping.isPresent()
                ? new Charge(shipping.name(), sign * shipping.cents(),
                        shipping.taxCategory(), shipping.taxRateBp())
                : null;
        var groups = calculation.taxTotals().stream()
                .map(total -> group(total.category(), total.rateBp(), sign * total.basisCents(),
                        sign * total.taxCents(), lines, tax, charge))
                .toList();

        return new EInvoiceDocument(credit ? Type.CREDIT_NOTE : Type.INVOICE, number, header.issueDate(),
                header.serviceFrom(), header.serviceTo(), header.dueDate(), seller(sender), buyer(recipient),
                bank(sender), lines, groups, sign * calculation.subtotalCents(), sign * calculation.discountCents(),
                sign * calculation.netCents(), sign * calculation.taxCents(), sign * calculation.grossCents(),
                referenced == null ? null : referenced.number(),
                referenced == null ? null : referenced.issueDate(), blankToNull(header.customerReference()), charge);
    }

    private Line line(InvoiceItemInfo item, int sign) {
        var unit = isBlank(item.unitUneceCode()) ? properties.defaultUnitCode() : item.unitUneceCode();

        return new Line(item.position(), item.description(), sign * item.quantityThousandths(), unit,
                item.unitPriceCents(), item.discountBp(), item.taxCategory(), item.taxRateBp(),
                sign * item.lineTotalCents());
    }

    /**
     * The share of the total discount of a group is the difference between its line totals and its taxable amount
     * without the charge, which is added after the discount.
     */
    private static TaxGroup group(String category, int rateBp, long basisCents, long taxCents, List<Line> lines,
            TaxSnapshot tax, Charge charge) {
        var lineSum = lines.stream()
                .filter(line -> line.taxCategory().equals(category) && line.taxRateBp() == rateBp)
                .mapToLong(Line::lineTotalCents)
                .sum();
        var chargeCents = charge != null && charge.category().equals(category) && charge.rateBp() == rateBp
                ? charge.cents()
                : 0;
        var reason = reason(category, tax);

        return new TaxGroup(category, rateBp, basisCents, taxCents, lineSum - (basisCents - chargeCents), reason);
    }

    /**
     * The exemption text of the profile counts for the category of the profile itself; other categories that need a
     * reason use the reason kept for them in the tax snapshot. Categories without an entry need no reason.
     */
    private static String reason(String category, TaxSnapshot tax) {
        String reason = null;

        if (category.equals(tax.defaultCategory()) && !isBlank(tax.exemptionText())) {
            reason = tax.exemptionText();
        } else if (tax.categoryReasons().containsKey(category)) {
            reason = tax.categoryReasons().get(category);

            if (isBlank(reason)) {
                throw new RuleViolationException("einvoice.error.exemptionReason");
            }
        }

        return reason;
    }

    private Party seller(SenderSnapshot sender) {
        return new Party(sender.name(), sender.owner(), sender.street(), sender.zip(), sender.city(),
                country(sender.country()), blankToNull(sender.email()), blankToNull(sender.phone()),
                blankToNull(sender.taxNumber()), blankToNull(sender.vatId()));
    }

    private Party buyer(RecipientSnapshot recipient) {
        return new Party(recipient.name(), blankToNull(recipient.contactName()), recipient.street(), recipient.zip(),
                recipient.city(), country(recipient.country()), blankToNull(recipient.invoiceEmail()), null, null,
                blankToNull(recipient.vatId()));
    }

    private static Bank bank(SenderSnapshot sender) {
        if (isBlank(sender.iban())) {
            return null;
        }

        var holder = isBlank(sender.owner()) ? sender.name() : sender.owner();

        return new Bank(holder, sender.iban().replaceAll("\\s", ""), blankToNull(sender.bic()));
    }

    private String country(String country) {
        return isBlank(country) ? properties.defaultCountry() : country;
    }
}
