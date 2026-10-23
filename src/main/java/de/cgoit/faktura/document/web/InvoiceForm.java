package de.cgoit.faktura.document.web;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.ShippingCharge;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.shared.DecimalInput;
import de.cgoit.faktura.shared.ParseErrors;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form data of the invoice editor. Dates come from date inputs (ISO), numbers in German notation. An empty discount
 * means "use the customer's discount", an empty payment term "use the default term".
 *
 * @param discountType {@code PERCENT} or {@code AMOUNT}
 * @param version the version of the draft when the editor opened it; empty for a new invoice
 * @param shippingAmount net shipping costs; empty for none
 * @param shippingTaxCategory tax category of the shipping costs; empty for the category of the tax profile
 * @param validUntil last day an offer is valid; empty for the default validity; not used for invoices
 */
public record InvoiceForm(
        String customerId,
        String contactId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceTo,
        String paymentDays,
        String subject,
        String headerText,
        String footerText,
        String discountType,
        String discountValue,
        List<ItemForm> items,
        Long version,
        String customerReference,
        String shippingName,
        String shippingAmount,
        String shippingTaxCategory,
        String shippingTaxRate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validUntil) {

    static InvoiceForm empty(LocalDate today) {
        return new InvoiceForm(null, null, today, null, null, null, null, null, null, "PERCENT", null, List.of(), null,
                null, null, null, null, null, null);
    }

    static InvoiceForm from(InvoiceInfo invoice) {
        var discount = invoice.totalDiscount();
        var amount = discount.type() == TotalDiscount.Type.AMOUNT;
        var value = switch (discount.type()) {
            case NONE -> null;
            case PERCENT -> DecimalInput.formatBasisPoints((int) discount.value());
            case AMOUNT -> DecimalInput.formatCents(discount.value());
        };
        var shipping = invoice.shipping();
        var days = invoice.dueDate() == null
                ? null
                : String.valueOf(ChronoUnit.DAYS.between(invoice.issueDate(), invoice.dueDate()));

        return new InvoiceForm(invoice.customerId(), invoice.contactId(), invoice.issueDate(), invoice.serviceFrom(),
                invoice.serviceTo(), days, invoice.subject(), invoice.headerText(), invoice.footerText(),
                amount ? "AMOUNT" : "PERCENT", value, invoice.items().stream()
                        .map(ItemForm::from)
                        .toList(), invoice.version(), invoice.customerReference(), shipping.name(),
                shipping.isPresent() ? DecimalInput.formatCents(shipping.cents()) : null, shipping.taxCategory(),
                shipping.isPresent() ? DecimalInput.formatBasisPoints(shipping.taxRateBp()) : null,
                invoice.validUntil());
    }

    /**
     * Returns a copy with another item list.
     */
    InvoiceForm withItems(List<ItemForm> newItems) {
        return new InvoiceForm(customerId, contactId, issueDate, serviceFrom, serviceTo, paymentDays, subject,
                headerText, footerText, discountType, discountValue, newItems, version, customerReference, shippingName,
                shippingAmount, shippingTaxCategory, shippingTaxRate, validUntil);
    }

    /**
     * Returns a copy with another invoice date.
     */
    InvoiceForm withIssueDate(LocalDate newIssueDate) {
        return new InvoiceForm(customerId, contactId, newIssueDate, serviceFrom, serviceTo, paymentDays, subject,
                headerText, footerText, discountType, discountValue, items, version, customerReference, shippingName,
                shippingAmount, shippingTaxCategory, shippingTaxRate, validUntil);
    }

    /**
     * Returns a copy with other header and footer texts.
     */
    InvoiceForm withTexts(String newHeaderText, String newFooterText) {
        return new InvoiceForm(customerId, contactId, issueDate, serviceFrom, serviceTo, paymentDays, subject,
                newHeaderText, newFooterText, discountType, discountValue, items, version, customerReference, shippingName,
                shippingAmount, shippingTaxCategory, shippingTaxRate, validUntil);
    }

    /**
     * Returns a mutable copy of the items; a missing list counts as empty.
     */
    List<ItemForm> itemList() {
        return items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    /**
     * Day that decides which tax profile applies: start of the service period, else the issue date.
     */
    LocalDate taxDay(LocalDate fallback) {
        if (serviceFrom != null) {
            return serviceFrom;
        }

        return issueDate != null ? issueDate : fallback;
    }

    /**
     * Converts the form to service input.
     *
     * @throws RuleViolationException with one violation per unreadable number, at the property of the input
     */
    InvoiceInput toInput() {
        var errors = new ParseErrors();
        var rows = itemList();
        var inputItems = new ArrayList<InvoiceItemInput>();

        for (var index = 0; index < rows.size(); index++) {
            inputItems.add(rows.get(index).toInput(index, errors));
        }

        var days = errors.parse("paymentDays", this::days, null);
        var discount = errors.parse("totalDiscount", this::discount, null);

        var shippingCents = errors.parse("shipping.cents", this::shippingCents, 0L);
        var shippingRate = errors.parse("shipping.taxRateBp", this::shippingRate, 0);

        errors.throwIfAny();

        var shipping = shippingCents == 0
                ? null
                : new ShippingCharge(shippingName, shippingCents, shippingTaxCategory, shippingRate);

        return new InvoiceInput(blank(customerId) ? null : customerId, issueDate, serviceFrom, serviceTo, days,
                subject, headerText, footerText, discount, inputItems, blank(contactId) ? null : contactId,
                customerReference, shipping, validUntil);
    }

    /**
     * Returns the version the editor read.
     *
     * @throws RuleViolationException with {@code invoice.error.conflict} if the form carries none: a save without the
     *         version could overwrite a parallel change
     */
    long requireVersion() {
        if (version == null) {
            throw new RuleViolationException("invoice.error.conflict");
        }

        return version;
    }

    private Integer days() {
        if (blank(paymentDays)) {
            return null;
        }

        try {
            return Integer.valueOf(paymentDays.strip());
        } catch (NumberFormatException e) {
            throw new RuleViolationException("validation.paymentDays");
        }
    }

    private long shippingCents() {
        return blank(shippingAmount) ? 0 : DecimalInput.parseCents(shippingAmount);
    }

    private int shippingRate() {
        return blank(shippingTaxRate) ? 0 : DecimalInput.parseBasisPoints(shippingTaxRate);
    }

    private TotalDiscount discount() {
        if (blank(discountValue)) {
            return null;
        }

        return "AMOUNT".equals(discountType)
                ? TotalDiscount.amount(DecimalInput.parseCents(discountValue))
                : TotalDiscount.percent(DecimalInput.parseBasisPoints(discountValue));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Hides the free texts from logs.
     */
    @Override
    public String toString() {
        return "InvoiceForm[...]";
    }
}
