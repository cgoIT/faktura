package de.cgoit.faktura.einvoice;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything an electronic invoice (EN 16931) contains, already calculated. The e-invoice module only maps these
 * values to XML and never recalculates: all amounts are whole cents, rates are basis points, quantities are
 * thousandths. For a credit note ({@link Type#CREDIT_NOTE}) all amounts and quantities are positive, as the standard
 * expects.
 *
 * @param referencedNumber for a credit note the number of the invoice it cancels
 * @param referencedDate   for a credit note the issue date of that invoice
 * @param buyerReference   reference of the customer (BT-10), {@code null} if none
 * @param charge           shipping costs as a charge on document level (BG-21), {@code null} if none; its tax is part of
 *                         the basis of the matching tax group
 */
public record EInvoiceDocument(
        Type type,
        String number,
        LocalDate issueDate,
        LocalDate serviceFrom,
        LocalDate serviceTo,
        LocalDate dueDate,
        Party seller,
        Party buyer,
        Bank bank,
        List<Line> lines,
        List<TaxGroup> taxGroups,
        long lineTotalCents,
        long allowanceCents,
        long netCents,
        long taxCents,
        long grossCents,
        String referencedNumber,
        LocalDate referencedDate,
        String buyerReference,
        Charge charge) {

    /**
     * Creates a document without buyer reference and charge.
     */
    public EInvoiceDocument(Type type, String number, LocalDate issueDate, LocalDate serviceFrom, LocalDate serviceTo,
            LocalDate dueDate, Party seller, Party buyer, Bank bank, List<Line> lines, List<TaxGroup> taxGroups,
            long lineTotalCents, long allowanceCents, long netCents, long taxCents, long grossCents,
            String referencedNumber, LocalDate referencedDate) {
        this(type, number, issueDate, serviceFrom, serviceTo, dueDate, seller, buyer, bank, lines, taxGroups,
                lineTotalCents, allowanceCents, netCents, taxCents, grossCents, referencedNumber, referencedDate,
                null, null);
    }

    /**
     * Kind of document: invoice (type code 380) or credit note (381).
     */
    public enum Type {
        INVOICE, CREDIT_NOTE
    }

    /**
     * Seller or buyer. Missing parts are {@code null}.
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
     * Bank account the seller is paid on.
     */
    public record Bank(String holder, String iban, String bic) {

        /**
         * Hides the account from logs.
         */
        @Override
        public String toString() {
            return "Bank[]";
        }
    }

    /**
     * One position.
     *
     * @param unitCode       UN/ECE unit code
     * @param lineTotalCents line total after the item discount
     */
    public record Line(
            int position,
            String description,
            long quantityThousandths,
            String unitCode,
            long unitPriceCents,
            int discountBp,
            String taxCategory,
            int taxRateBp,
            long lineTotalCents) {

        /**
         * Hides the free text from logs.
         */
        @Override
        public String toString() {
            return "Line[position=" + position + "]";
        }
    }

    /**
     * A charge on document level, here the shipping costs.
     *
     * @param name     reason text printed with the charge
     * @param cents    net amount, positive
     * @param category EN 16931 tax category of the charge
     * @param rateBp   tax rate in basis points
     */
    public record Charge(String name, long cents, String category, int rateBp) {
    }

    /**
     * Tax of one group of equal category and rate.
     *
     * @param basisCents      taxable amount after the total discount, including a charge of this group
     * @param allowanceCents  share of the total discount that belongs to this group (not reduced by the charge)
     * @param exemptionReason reason for categories without tax, {@code null} if not needed
     */
    public record TaxGroup(
            String category,
            int rateBp,
            long basisCents,
            long taxCents,
            long allowanceCents,
            String exemptionReason) {
    }

    /**
     * Hides personal data from logs.
     */
    @Override
    public String toString() {
        return "EInvoiceDocument[number=" + number + "]";
    }
}
