package de.cgoit.faktura.document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import de.cgoit.faktura.shared.Rounding;

/**
 * Pure calculation of line totals, discounts and tax for a document, in the order: line amount, line discount, subtotal, total discount, tax per rate, shipping.
 * All amounts are whole cents, rates are basis points, rounding is half up. The calculation never looks at the tax
 * regime: a tax-exempt item simply has rate 0.
 */
public final class InvoiceCalculator {

    private static final BigInteger QUANTITY_AND_PERCENT_DIVISOR = BigInteger.valueOf(1_000L * 10_000L);
    private static final int FULL_PERCENT_BP = 10_000;

    private InvoiceCalculator() {
    }

    /**
     * Calculates the document: line total = quantity x price less the item discount (rounded to cents), subtotal,
     * total discount (capped at the subtotal), tax per group of category and rate. The total discount is spread over
     * the groups in proportion to their line totals, remainders by the largest-remainder method, so the shares add up
     * to the discount exactly.
     */
    public static Calculation calculate(List<CalculationItem> items, TotalDiscount discount) {
        return calculate(items, discount, ShippingCharge.none());
    }

    /**
     * Like {@link #calculate(List, TotalDiscount)} with shipping costs. The shipping amount is net, is added after the
     * total discount and is neither reduced by it nor part of what the discount is spread over; its tax joins the group
     * of the same category and rate, or forms a group of its own.
     */
    public static Calculation calculate(List<CalculationItem> items, TotalDiscount discount, ShippingCharge shipping) {
        var lineTotals = new ArrayList<Long>();
        var groups = new LinkedHashMap<String, Group>();
        var subtotal = 0L;

        for (var item : items) {
            var lineTotal = lineTotal(item);
            lineTotals.add(lineTotal);
            subtotal += lineTotal;
            var group = groups.computeIfAbsent(item.taxCategory() + "/" + item.taxRateBp(),
                    key -> new Group(item.taxCategory(), item.taxRateBp()));
            group.sum += lineTotal;
        }

        var discountCents = Math.min(totalDiscountCents(subtotal, discount), subtotal);
        spreadDiscount(new ArrayList<>(groups.values()), subtotal, discountCents);

        var shippingCents = shipping.isPresent() ? shipping.cents() : 0L;

        if (shipping.isPresent()) {
            groups.computeIfAbsent(shipping.taxCategory() + "/" + shipping.taxRateBp(),
                    key -> new Group(shipping.taxCategory(), shipping.taxRateBp()));
        }

        var taxTotals = new ArrayList<TaxTotal>();
        var tax = 0L;

        for (var group : groups.values()) {
            var carriesShipping = shipping.isPresent()
                    && Objects.equals(group.category, shipping.taxCategory())
                    && group.rateBp == shipping.taxRateBp();
            var basis = group.sum - group.share + (carriesShipping ? shippingCents : 0);
            var groupTax = Rounding.percentOfHalfUp(basis, group.rateBp);
            taxTotals.add(new TaxTotal(group.category, group.rateBp, basis, groupTax));
            tax += groupTax;
        }

        var net = subtotal - discountCents + shippingCents;

        return new Calculation(List.copyOf(lineTotals), subtotal, discountCents, shippingCents, net, tax, net + tax,
                List.copyOf(taxTotals));
    }

    /**
     * Line total of one item: quantity x unit price x (100 % - item discount), rounded half up to cents.
     */
    public static long lineTotal(CalculationItem item) {
        var numerator = BigInteger.valueOf(item.quantityThousandths())
                .multiply(BigInteger.valueOf(item.unitPriceCents()))
                .multiply(BigInteger.valueOf(FULL_PERCENT_BP - item.discountBp()));

        return Rounding.divideHalfUp(numerator, QUANTITY_AND_PERCENT_DIVISOR);
    }

    private static long totalDiscountCents(long subtotal, TotalDiscount discount) {
        return switch (discount.type()) {
            case NONE -> 0;
            case PERCENT -> Rounding.percentOfHalfUp(subtotal, discount.value());
            case AMOUNT -> discount.value();
        };
    }

    private static void spreadDiscount(List<Group> groups, long subtotal, long discountCents) {
        if (discountCents == 0 || subtotal == 0) {
            return;
        }

        var total = BigInteger.valueOf(subtotal);
        var assigned = 0L;

        for (var group : groups) {
            var exact = BigInteger.valueOf(discountCents)
                    .multiply(BigInteger.valueOf(group.sum));
            group.share = exact.divide(total)
                    .longValueExact();
            group.remainder = exact.mod(total);
            assigned += group.share;
        }

        var ranked = groups.stream()
                .sorted(Comparator.comparing((Group group) -> group.remainder)
                        .reversed())
                .toList();
        var missing = discountCents - assigned;

        for (var i = 0; i < missing; i++) {
            ranked.get(i).share++;
        }
    }

    private static final class Group {

        private final String category;
        private final int rateBp;
        private long sum;
        private long share;
        private BigInteger remainder = BigInteger.ZERO;

        private Group(String category, int rateBp) {
            this.category = category;
            this.rateBp = rateBp;
        }
    }
}
