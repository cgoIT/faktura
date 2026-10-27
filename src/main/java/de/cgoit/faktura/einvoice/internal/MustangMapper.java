package de.cgoit.faktura.einvoice.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import de.cgoit.faktura.einvoice.EInvoiceDocument;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Bank;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Line;
import de.cgoit.faktura.einvoice.EInvoiceDocument.Party;
import de.cgoit.faktura.einvoice.EInvoiceDocument.TaxGroup;
import org.mustangproject.Allowance;
import org.mustangproject.BankDetails;
import org.mustangproject.Charge;
import org.mustangproject.Contact;
import org.mustangproject.Invoice;
import org.mustangproject.Item;
import org.mustangproject.Product;
import org.mustangproject.ReferencedDocument;
import org.mustangproject.TradeParty;

/**
 * Maps the calculated {@link EInvoiceDocument} to the Mustang model. Discounts are passed as allowances so that the
 * amounts Mustang writes equal the stored amounts; {@link TotalsCheck} verifies this on the generated XML.
 */
final class MustangMapper {

    private static final String CURRENCY = "EUR";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MustangMapper() {
    }

    static Invoice map(EInvoiceDocument document, String discountReason) {
        var invoice = new Invoice();

        invoice.setNumber(document.number());
        invoice.setIssueDate(date(document.issueDate()));
        invoice.setDueDate(date(document.dueDate()));
        invoice.setDeliveryDate(date(document.serviceFrom() != null ? document.serviceFrom() : document.issueDate()));

        if (document.serviceFrom() != null && document.serviceTo() != null) {
            invoice.setDetailedDeliveryPeriod(date(document.serviceFrom()), date(document.serviceTo()));
        }

        if (document.buyerReference() != null) {
            invoice.setReferenceNumber(document.buyerReference());
        }

        invoice.setCurrency(CURRENCY);
        invoice.setPaymentReference(document.number());
        invoice.setSender(seller(document.seller(), document.bank()));
        invoice.setRecipient(buyer(document.buyer()));

        if (document.type() == EInvoiceDocument.Type.CREDIT_NOTE) {
            invoice.setCreditNote();
            invoice.setInvoiceReferencedDocuments(
                    List.of(new ReferencedDocument(document.referencedNumber(), date(document.referencedDate()))));
        }

        for (var line : document.lines()) {
            invoice.addItem(item(line, exemptionReason(document, line), discountReason));
        }

        for (var group : document.taxGroups()) {
            if (group.allowanceCents() > 0) {
                invoice.addAllowance(new Allowance(euro(group.allowanceCents()))
                        .setTaxRateApplicablePercent(percent(group.rateBp()))
                        .setTaxCategoryCode(group.category())
                        .setReason(discountReason));
            }
        }

        if (document.charge() != null) {
            var charge = document.charge();
            var mapped = new Charge(euro(charge.cents()))
                    .setTaxRateApplicablePercent(percent(charge.rateBp()))
                    .setTaxCategoryCode(charge.category())
                    .setReason(charge.name());
            var reason = document.taxGroups().stream()
                    .filter(group -> group.category().equals(charge.category()) && group.rateBp() == charge.rateBp())
                    .map(TaxGroup::exemptionReason)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            if (reason != null) {
                mapped.setTaxExemptionReason(reason);
            }

            invoice.addCharge(mapped);
        }

        return invoice;
    }

    private static Item item(Line line, String exemptionReason, String discountReason) {
        var product = new Product(line.description(), "", line.unitCode(), percent(line.taxRateBp()));

        product.setTaxCategoryCode(line.taxCategory());

        if (exemptionReason != null) {
            product.setTaxExemptionReason(exemptionReason);
        }

        var quantity = BigDecimal.valueOf(line.quantityThousandths(), 3);
        var item = new Item(product, euro(line.unitPriceCents()), quantity);
        var gross = euro(line.unitPriceCents()).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        var allowance = gross.subtract(euro(line.lineTotalCents()));

        if (allowance.signum() > 0) {
            item.addAllowance(new Allowance(allowance)
                    .setPercent(percent(line.discountBp()))
                    .setBasisAmount(gross)
                    .setReason(discountReason));
        }

        return item;
    }

    private static String exemptionReason(EInvoiceDocument document, Line line) {
        return document.taxGroups().stream()
                .filter(group -> group.category().equals(line.taxCategory()) && group.rateBp() == line.taxRateBp())
                .map(TaxGroup::exemptionReason)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static TradeParty seller(Party party, Bank bank) {
        var seller = party(party);

        seller.setID(party.taxNumber() != null && !party.taxNumber().isBlank() ? party.taxNumber() : party.vatId());

        if (party.taxNumber() != null && !party.taxNumber().isBlank()) {
            seller.addTaxID(party.taxNumber());
        }

        if (party.vatId() != null && !party.vatId().isBlank()) {
            seller.addVATID(party.vatId());
        }

        if (bank != null) {
            var details = new BankDetails(bank.iban(), bank.bic() == null || bank.bic().isBlank() ? null : bank.bic());

            if (bank.holder() != null) {
                details.setAccountName(bank.holder());
            }

            seller.addBankDetails(details);
        }

        return seller;
    }

    private static TradeParty buyer(Party party) {
        var buyer = party(party);

        if (party.vatId() != null && !party.vatId().isBlank()) {
            buyer.addVATID(party.vatId());
        }

        return buyer;
    }

    private static TradeParty party(Party party) {
        var result = new TradeParty(party.name(), party.street(), party.zip(), party.city(), party.country());

        if (party.contact() != null || party.email() != null || party.phone() != null) {
            result.setContact(new Contact(party.contact(), party.phone(), party.email()));
        }

        return result;
    }

    private static BigDecimal euro(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static BigDecimal percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints).divide(HUNDRED, 2, RoundingMode.UNNECESSARY);
    }

    private static Date date(LocalDate date) {
        return date == null
                ? null
                : Date.from(date.atStartOfDay(ZoneId.systemDefault())
                        .toInstant());
    }
}
