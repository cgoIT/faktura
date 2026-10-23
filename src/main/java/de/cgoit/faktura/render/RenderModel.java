package de.cgoit.faktura.render;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the invoice template prints, fully calculated. The template does no arithmetic and reads nothing else:
 * for a finalized document the model is built from the snapshots stored with the document, never from live master
 * data. All amounts are whole cents.
 *
 * @param kind             invoice, cancellation or offer document
 * @param draft            {@code true} for a preview of a draft: no number, marked as draft
 * @param number           document number, {@code null} for a draft
 * @param customerNumber   number of the recipient
 * @param discountCents    total discount actually applied, shown as a deduction when not zero
 * @param taxLines         tax per rate; empty if no tax is shown (e.g. small business)
 * @param exemptionText    legal text for a tax exemption, e.g. the reference to section 19 UStG
 * @param noticeText       additional notice of the tax profile
 * @param account          bank account for payments, {@code null} if none is printed
 * @param referencedNumber for a cancellation the number of the cancelled invoice
 * @param referencedDate   for a cancellation the issue date of the cancelled invoice
 * @param customerReference reference of the customer, {@code null} if none
 * @param shippingName     label of the shipping costs, {@code null} falls back to a default label
 * @param shippingCents    net shipping costs added after the discount, 0 if none
 * @param validUntil       for an offer the last day it is valid, {@code null} otherwise
 */
public record RenderModel(
        Kind kind,
        boolean draft,
        String number,
        LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        LocalDate dueDate,
        Party sender,
        Party recipient,
        String customerNumber,
        String headerText,
        String footerText,
        List<Item> items,
        long subtotalCents,
        long discountCents,
        long netCents,
        long taxCents,
        long grossCents,
        List<TaxLine> taxLines,
        String exemptionText,
        String noticeText,
        Account account,
        String referencedNumber,
        LocalDate referencedDate,
        String customerReference,
        String shippingName,
        long shippingCents,
        LocalDate validUntil) {

    /**
     * Creates a model without a validity date.
     */
    public RenderModel(Kind kind, boolean draft, String number, LocalDate issueDate, LocalDate serviceFrom,
            LocalDate serviceTo, LocalDate dueDate, Party sender, Party recipient, String customerNumber,
            String headerText, String footerText, List<Item> items, long subtotalCents, long discountCents,
            long netCents, long taxCents, long grossCents, List<TaxLine> taxLines, String exemptionText,
            String noticeText, Account account, String referencedNumber, LocalDate referencedDate,
            String customerReference, String shippingName, long shippingCents) {
        this(kind, draft, number, issueDate, serviceFrom, serviceTo, dueDate, sender, recipient, customerNumber,
                headerText, footerText, items, subtotalCents, discountCents, netCents, taxCents, grossCents,
                taxLines, exemptionText, noticeText, account, referencedNumber, referencedDate, customerReference,
                shippingName, shippingCents, null);
    }

    /**
     * Creates a model without customer reference and shipping costs.
     */
    public RenderModel(Kind kind, boolean draft, String number, LocalDate issueDate, LocalDate serviceFrom,
            LocalDate serviceTo, LocalDate dueDate, Party sender, Party recipient, String customerNumber,
            String headerText, String footerText, List<Item> items, long subtotalCents, long discountCents,
            long netCents, long taxCents, long grossCents, List<TaxLine> taxLines, String exemptionText,
            String noticeText, Account account, String referencedNumber, LocalDate referencedDate) {
        this(kind, draft, number, issueDate, serviceFrom, serviceTo, dueDate, sender, recipient, customerNumber,
                headerText, footerText, items, subtotalCents, discountCents, netCents, taxCents, grossCents,
                taxLines, exemptionText, noticeText, account, referencedNumber, referencedDate, null, null, 0, null);
    }

    /**
     * Type of printed document.
     */
    public enum Kind {
        INVOICE, CANCELLATION, OFFER
    }

    /**
     * Sender or recipient as printed. Missing parts are {@code null}.
     */
    public record Party(
            String name,
            String contact,
            String street,
            String zip,
            String city,
            String country,
            String email,
            String phone,
            String taxNumber,
            String vatId) {

        /**
         * Hides personal data from logs.
         */
        @Override
        public String toString() {
            return "Party[]";
        }
    }

    /**
     * One printed position.
     */
    public record Item(
            int position,
            String description,
            long quantityThousandths,
            String unitName,
            String unitNamePlural,
            long unitPriceCents,
            int discountBp,
            int taxRateBp,
            long lineTotalCents) {

        /**
         * Hides the free text from logs.
         */
        @Override
        public String toString() {
            return "Item[position=" + position + "]";
        }
    }

    /**
     * Tax of one rate.
     */
    public record TaxLine(int rateBp, long basisCents, long taxCents) {
    }

    /**
     * Bank account printed for payments and encoded in the payment QR code.
     */
    public record Account(String holder, String bankName, String iban, String bic) {

        /**
         * Hides bank details from logs.
         */
        @Override
        public String toString() {
            return "Account[]";
        }
    }

    /**
     * Shows only technical values: the model holds personal data that must not end up in logs.
     */
    @Override
    public String toString() {
        return "RenderModel[kind=" + kind + ", number=" + number + "]";
    }
}
