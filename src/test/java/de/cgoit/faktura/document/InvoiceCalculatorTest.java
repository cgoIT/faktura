package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InvoiceCalculatorTest {

    private static CalculationItem exempt(long quantityThousandths, long priceCents, int discountBp) {
        return new CalculationItem(quantityThousandths, priceCents, discountBp, "E", 0);
    }

    private static CalculationItem taxed(long priceCents, int rateBp) {
        return new CalculationItem(1000, priceCents, 0, "S", rateBp);
    }

    @Test
    @DisplayName("small business example ends at 485,45 EUR")
    void smallBusinessExample() {
        var items = List.of(exempt(6000, 8500, 1000), exempt(1000, 1200, 0), exempt(1000, 4000, 0));

        var result = InvoiceCalculator.calculate(items, TotalDiscount.percent(500));

        assertThat(result.lineTotals()).containsExactly(45_900L, 1_200L, 4_000L);
        assertThat(result.subtotalCents()).isEqualTo(51_100);
        assertThat(result.discountCents()).isEqualTo(2_555);
        assertThat(result.netCents()).isEqualTo(48_545);
        assertThat(result.taxCents()).isZero();
        assertThat(result.grossCents()).isEqualTo(48_545);
        assertThat(result.taxTotals()).containsExactly(new TaxTotal("E", 0, 48_545, 0));
    }

    static Stream<Arguments> lineRounding() {
        return Stream.of(
                Arguments.of(500, 1, 0, 1L),
                Arguments.of(1500, 1, 0, 2L),
                Arguments.of(400, 1, 0, 0L),
                Arguments.of(1000, 8500, 1000, 7650L),
                Arguments.of(1000, 5, 1000, 5L),
                Arguments.of(1000, 15, 1000, 14L),
                Arguments.of(2500, 3333, 0, 8333L),
                Arguments.of(1000, 1999, 10_000, 0L),
                Arguments.of(0, 1999, 0, 0L));
    }

    @ParameterizedTest(name = "qty {0}/1000 x {1} ct less {2} bp = {3} ct")
    @MethodSource("lineRounding")
    void lineTotalRoundsHalfUp(long quantity, long price, int discountBp, long expected) {
        assertThat(InvoiceCalculator.lineTotal(exempt(quantity, price, discountBp))).isEqualTo(expected);
    }

    static Stream<Arguments> totalDiscounts() {
        return Stream.of(
                Arguments.of(TotalDiscount.none(), 0L),
                Arguments.of(TotalDiscount.percent(500), 51L),
                Arguments.of(TotalDiscount.percent(10_000), 1010L),
                Arguments.of(TotalDiscount.amount(300), 300L),
                Arguments.of(TotalDiscount.amount(5000), 1010L));
    }

    @ParameterizedTest
    @MethodSource("totalDiscounts")
    void totalDiscountIsRoundedAndCappedAtSubtotal(TotalDiscount discount, long expectedDiscount) {
        var result = InvoiceCalculator.calculate(List.of(exempt(1000, 1010, 0)), discount);

        assertThat(result.discountCents()).isEqualTo(expectedDiscount);
        assertThat(result.netCents()).isEqualTo(1010 - expectedDiscount);
    }

    @Test
    void regularTaxOnSingleRate() {
        var result = InvoiceCalculator.calculate(List.of(taxed(10_000, 1900)), TotalDiscount.none());

        assertThat(result.netCents()).isEqualTo(10_000);
        assertThat(result.taxCents()).isEqualTo(1_900);
        assertThat(result.grossCents()).isEqualTo(11_900);
    }

    @Test
    void taxIsRoundedHalfUp() {
        var result = InvoiceCalculator.calculate(List.of(taxed(1050, 1900)), TotalDiscount.none());

        assertThat(result.taxCents()).isEqualTo(200);
    }

    @Test
    void totalDiscountIsSpreadOverTaxGroupsWithLargestRemainder() {
        var items = List.of(taxed(10_000, 1900), taxed(5_000, 700), exempt(1000, 5_000, 0));

        var result = InvoiceCalculator.calculate(items, TotalDiscount.amount(1001));

        assertThat(result.taxTotals()).containsExactly(
                new TaxTotal("S", 1900, 9_499, 1_805),
                new TaxTotal("S", 700, 4_750, 333),
                new TaxTotal("E", 0, 4_750, 0));
        assertThat(result.taxTotals().stream().mapToLong(TaxTotal::basisCents).sum()).isEqualTo(result.netCents());
        assertThat(result.netCents()).isEqualTo(18_999);
        assertThat(result.taxCents()).isEqualTo(2_138);
        assertThat(result.grossCents()).isEqualTo(21_137);
    }

    @Test
    void itemsWithSameCategoryAndRateShareOneGroup() {
        var result = InvoiceCalculator.calculate(List.of(taxed(1000, 1900), taxed(2000, 1900)),
                TotalDiscount.percent(1000));

        assertThat(result.taxTotals()).containsExactly(new TaxTotal("S", 1900, 2_700, 513));
    }

    @Test
    void sameRateInDifferentCategoriesStaysSeparate() {
        var items = List.of(taxed(1000, 0), exempt(1000, 1000, 0));

        var result = InvoiceCalculator.calculate(items, TotalDiscount.none());

        assertThat(result.taxTotals()).hasSize(2);
    }

    @Test
    void fullDiscountLeavesNothingToTax() {
        var result = InvoiceCalculator.calculate(List.of(taxed(10_000, 1900)), TotalDiscount.percent(10_000));

        assertThat(result.netCents()).isZero();
        assertThat(result.taxCents()).isZero();
        assertThat(result.grossCents()).isZero();
    }

    @Test
    void emptyDocumentIsZero() {
        var result = InvoiceCalculator.calculate(List.of(), TotalDiscount.percent(500));

        assertThat(result.grossCents()).isZero();
        assertThat(result.lineTotals()).isEmpty();
        assertThat(result.taxTotals()).isEmpty();
    }

    @Test
    @DisplayName("shipping is added after the discount and is not reduced by it (small business)")
    void shippingIsAddedAfterTheDiscount() {
        var items = List.of(exempt(6000, 8500, 1000), exempt(1000, 1200, 0), exempt(1000, 4000, 0));
        var shipping = new ShippingCharge("Versand", 495, "E", 0);

        var result = InvoiceCalculator.calculate(items, TotalDiscount.percent(500), shipping);

        assertThat(result.subtotalCents()).isEqualTo(51_100);
        assertThat(result.discountCents()).isEqualTo(2_555);
        assertThat(result.shippingCents()).isEqualTo(495);
        assertThat(result.netCents()).isEqualTo(49_040);
        assertThat(result.grossCents()).isEqualTo(49_040);
        assertThat(result.taxTotals()).containsExactly(new TaxTotal("E", 0, 49_040, 0));
    }

    @Test
    @DisplayName("shipping with the rate of an item shares its tax group, the discount is not spread onto it")
    void shippingSharesTheGroupOfAnItemWithTheSameRate() {
        var items = List.of(taxed(10_000, 1900), new CalculationItem(1000, 10_000, 0, "S", 700));

        var result = InvoiceCalculator.calculate(items, TotalDiscount.percent(1000),
                new ShippingCharge("Versand", 1_000, "S", 1900));

        assertThat(result.discountCents()).isEqualTo(2_000);
        assertThat(result.taxTotals()).containsExactly(
                new TaxTotal("S", 1900, 10_000, 1_900),
                new TaxTotal("S", 700, 9_000, 630));
        assertThat(result.taxTotals().get(0)).isEqualTo(new TaxTotal("S", 1900, 10_000, 1_900));
        assertThat(result.netCents()).isEqualTo(19_000);
        assertThat(result.taxCents()).isEqualTo(2_530);
        assertThat(result.grossCents()).isEqualTo(21_530);
    }

    @Test
    @DisplayName("shipping with an own rate forms its own tax group")
    void shippingWithAnOwnRateFormsAGroup() {
        var result = InvoiceCalculator.calculate(List.of(taxed(10_000, 1900)), TotalDiscount.none(),
                new ShippingCharge("Versand", 500, "S", 700));

        assertThat(result.taxTotals()).containsExactly(
                new TaxTotal("S", 1900, 10_000, 1_900),
                new TaxTotal("S", 700, 500, 35));
        assertThat(result.netCents()).isEqualTo(10_500);
        assertThat(result.grossCents()).isEqualTo(12_435);
    }

    @Test
    @DisplayName("shipping is rounded half up on its own tax")
    void shippingTaxRoundsHalfUp() {
        var result = InvoiceCalculator.calculate(List.of(taxed(10_000, 1900)), TotalDiscount.none(),
                new ShippingCharge("Versand", 295, "S", 1900));

        assertThat(result.taxTotals()).containsExactly(new TaxTotal("S", 1900, 10_295, 1_956));
    }

    @Test
    @DisplayName("an amount discount is capped by the item subtotal, shipping stays")
    void discountCapDoesNotTouchShipping() {
        var result = InvoiceCalculator.calculate(List.of(exempt(1000, 1000, 0)), TotalDiscount.amount(5_000),
                new ShippingCharge("Versand", 300, "E", 0));

        assertThat(result.discountCents()).isEqualTo(1_000);
        assertThat(result.netCents()).isEqualTo(300);
    }

    @Test
    @DisplayName("without shipping the result equals the two-argument calculation")
    void noShippingChangesNothing() {
        var items = List.of(taxed(10_000, 1900));

        assertThat(InvoiceCalculator.calculate(items, TotalDiscount.percent(500), ShippingCharge.none()))
                .isEqualTo(InvoiceCalculator.calculate(items, TotalDiscount.percent(500)));
    }
}
