package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.customer.ContactInput;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.TotalDiscount;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator(
            Validation.buildDefaultValidatorFactory().getValidator());

    private static CustomerInput customer(String name, String zip, String country, Integer days, Integer discount) {
        return new CustomerInput(name, null, zip, null, country, null, null, days, discount, null);
    }

    private static InvoiceItemInput item(String description, long price) {
        return new InvoiceItemInput(description, 1000, "Std", price, 0, "E", 0, null);
    }

    private static InvoiceInput invoice(List<InvoiceItemInput> items) {
        return new InvoiceInput("c1", LocalDate.parse("2026-10-20"), null, null, 14, null, null, null, null, items);
    }

    @Test
    void violationsOfListElementsNameTheIndexedPath() {
        var items = List.of(item("ok", 100), item(" ", -1), item("x".repeat(2001), 100));

        assertThat(validator.violations(invoice(items))).containsExactly(
                new FieldViolation("items[1].description", "validation.required"),
                new FieldViolation("items[1].unitPriceCents", "validation.money"),
                new FieldViolation("items[2].description", "validation.tooLong"));
    }

    @Test
    void indexesAreOrderedAsNumbers() {
        var items = new ArrayList<InvoiceItemInput>();

        for (var i = 0; i < 12; i++) {
            items.add(item(i == 2 || i == 10 ? " " : "ok", 100));
        }

        assertThat(validator.violations(invoice(items))).containsExactly(
                new FieldViolation("items[2].description", "validation.required"),
                new FieldViolation("items[10].description", "validation.required"));
    }

    @Test
    void emptyListNullListAndNullElementAreHandled() {
        assertThat(validator.violations(invoice(List.of()))).isEmpty();
        assertThat(validator.violations(invoice(null)))
                .containsExactly(new FieldViolation("items", "validation.required"));

        var withNull = new ArrayList<InvoiceItemInput>();

        withNull.add(item("ok", 100));
        withNull.add(null);

        assertThat(validator.violations(invoice(withNull)))
                .containsExactly(new FieldViolation("items[1].<list element>", "validation.required"));
    }

    @Test
    void totalDiscountIsCheckedAsANestedProperty() {
        var input = new InvoiceInput("c1", LocalDate.parse("2026-10-20"), null, null, 14, null, null, null,
                TotalDiscount.percent(10_001), List.of(item("ok", 100)));

        assertThat(validator.violations(input))
                .containsExactly(new FieldViolation("totalDiscount.valid", "validation.percent"));
        assertThat(validator.violations(new InvoiceInput("c1", LocalDate.parse("2026-10-20"), null, null, 14, null,
                null, null, TotalDiscount.amount(10_001), List.of(item("ok", 100))))).isEmpty();
    }

    @Test
    void validInputHasNoViolations() {
        assertThat(validator.violations(customer("Müller GmbH", "12345", "DE", 14, 500))).isEmpty();
    }

    @Test
    void violationsNameTheMessageKeyAndTheProperty() {
        var violations = validator.violations(customer(" ", "1".repeat(21), "DEU", 400, 20_000));

        assertThat(violations).containsExactly(
                new FieldViolation("country", "validation.country"),
                new FieldViolation("discountBp", "validation.percent"),
                new FieldViolation("name", "validation.required"),
                new FieldViolation("paymentDays", "validation.paymentDays"),
                new FieldViolation("zip", "validation.tooLong"));
    }

    @Test
    void checkThrowsForTheFirstViolationWithItsProperty() {
        assertThatThrownBy(() -> validator.check(customer("Müller", "1".repeat(21), "DE", null, null)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.tooLong")
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("zip"))
                .satisfies(e -> assertThat(((RuleViolationException) e).violations()).hasSize(1));
    }

    @Test
    void checkCarriesAllViolations() {
        assertThatThrownBy(() -> validator.check(customer(" ", "1".repeat(21), "DE", null, null)))
                .satisfies(e -> assertThat(((RuleViolationException) e).violations()).containsExactly(
                        new FieldViolation("name", "validation.required"),
                        new FieldViolation("zip", "validation.tooLong")));
    }

    @Test
    void checkAcceptsValidInput() {
        validator.check(new ContactInput("MR", "Max", "Mustermann", "max@example.org", "030 123", null, "Vorstand"));
    }

    @Test
    void contactNeedsALastNameAndOnlyKnownSalutations() {
        assertThat(validator.violations(new ContactInput("XX", "Max", " ", null, null, null, null)))
                .containsExactly(new FieldViolation("lastName", "validation.required"),
                        new FieldViolation("salutation", "contact.error.salutation"));
    }
}
