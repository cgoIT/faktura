package de.cgoit.faktura.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class CustomerServiceTest {

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM number_sequence WHERE scope = 'CUSTOMER'").update();
    }

    @Test
    void createAssignsConsecutiveNumbersWithoutYear() {
        var first = customers.create(input("Müller GmbH"));
        var second = customers.create(input("Schmidt AG"));

        assertThat(first.number()).isEqualTo("KD0001");
        assertThat(second.number()).isEqualTo("KD0002");
        assertThat(first.archived()).isFalse();
        assertThat(first.country()).isEqualTo("DE");
    }

    @Test
    void einvoiceModeDefaultsToNoneAndCanBeChanged() {
        var created = customers.create(input("Müller GmbH"));

        assertThat(created.einvoiceMode()).isEqualTo(EInvoiceMode.NONE);

        var updated = customers.update(created.id(), new CustomerInput("Müller GmbH", null, null, null, "DE", null,
                null, null, null, null, EInvoiceMode.ZUGFERD));

        assertThat(updated.einvoiceMode()).isEqualTo(EInvoiceMode.ZUGFERD);
        assertThat(customers.find(created.id()).orElseThrow().einvoiceMode()).isEqualTo(EInvoiceMode.ZUGFERD);
    }

    @Test
    void contactKeepsPhoneAndMobileSeparately() {
        var customer = customers.create(input("Müller GmbH"));

        var contact = customers.addContact(customer.id(),
                new ContactInput(null, "Erika", "Beispiel", "erika@example.org", " 0123 456 ", "0171 789", null));

        assertThat(contact.phone()).isEqualTo("0123 456");
        assertThat(contact.mobile()).isEqualTo("0171 789");
        assertThat(customers.findContact(contact.id()).orElseThrow().mobile()).isEqualTo("0171 789");

        var changed = customers.updateContact(customer.id(), contact.id(),
                new ContactInput(null, "Erika", "Beispiel", null, null, " ", null));

        assertThat(changed.mobile()).isNull();
        assertThatThrownBy(() -> customers.addContact(customer.id(),
                new ContactInput(null, "A", "B", null, "1", "9".repeat(51), null)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.tooLong");
    }

    @Test
    void violationsNameThePropertyOfTheInput() {
        var tooLong = new CustomerInput("Müller GmbH", "x".repeat(201), null, null, "DE", null, null, null, null, null);
        var badVat = new CustomerInput("Müller GmbH", null, null, null, "DE", "DE12", null, null, null, null);
        var badMail = new CustomerInput("Müller GmbH", null, null, null, "DE", null, "kaputt", null, null, null);
        var customer = customers.create(input("Schmidt AG"));

        assertThatThrownBy(() -> customers.create(tooLong)).isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.tooLong")
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("street"));
        assertThatThrownBy(() -> customers.create(badVat))
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("vatId"));
        assertThatThrownBy(() -> customers.create(badMail))
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("invoiceEmail"));
        assertThatThrownBy(() -> customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null,
                null, null, null, EInvoiceMode.NONE, "a@example.test, kaputt")))
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("mailRecipients"));
        assertThat(customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null, EInvoiceMode.NONE, " a@example.test ;b@example.test,, ")).mailRecipients())
                .isEqualTo("a@example.test, b@example.test");
        assertThatThrownBy(() -> customers.addContact(customer.id(), new ContactInput("Erika", " ")))
                .hasMessage("validation.required")
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("lastName"));
        assertThatThrownBy(() -> customers.addContact(customer.id(), new ContactInput(" ", "Beispiel")))
                .hasMessage("validation.required")
                .satisfies(e -> assertThat(((RuleViolationException) e).field()).isEqualTo("firstName"));
    }

    @Test
    void failedSaveKeepsNumberFree() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            customers.create(input("Müller GmbH"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(customers.create(input("Schmidt AG")).number()).isEqualTo("KD0001");
    }

    @Test
    void normalizesAndStoresOptionalValues() {
        var created = customers.create(new CustomerInput(" Müller GmbH ", " ", "12345", "Musterstadt", "de",
                "de 123 456 789", " rechnung@example.org ", 30, 550, " "));

        assertThat(created.name()).isEqualTo("Müller GmbH");
        assertThat(created.street()).isNull();
        assertThat(created.note()).isNull();
        assertThat(created.country()).isEqualTo("DE");
        assertThat(created.vatId()).isEqualTo("DE123456789");
        assertThat(created.invoiceEmail()).isEqualTo("rechnung@example.org");
        assertThat(created.paymentDays()).isEqualTo(30);
        assertThat(created.discountBp()).isEqualTo(550);
    }

    @Test
    void updateKeepsNumber() {
        var created = customers.create(input("Müller GmbH"));

        var changed = customers.update(created.id(), input("Müller & Söhne GmbH"));

        assertThat(changed.number()).isEqualTo("KD0001");
        assertThat(changed.name()).isEqualTo("Müller & Söhne GmbH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE12345678", "DE1234567890", "DE12345678A", "D1123456789", "AT1", "AT U 12345678901234"})
    void invalidVatIdIsRejected(String vatId) {
        assertThatThrownBy(() -> customers.create(withVatId(vatId)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.vatId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE123456789", "ATU12345678", "FR12345678901", "NL123456789B01"})
    void validVatIdIsAccepted(String vatId) {
        assertThat(customers.create(withVatId(vatId)).vatId()).isEqualTo(vatId);
    }

    @Test
    void otherInvalidInputIsRejected() {
        assertThatThrownBy(() -> customers.create(input(" "))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> customers.create(input("x".repeat(201)))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> customers.create(new CustomerInput("A", null, null, null, "DEU", null, null, null,
                null, null))).hasMessage("validation.country");
        assertThatThrownBy(() -> customers.create(new CustomerInput("A", null, null, null, "DE", null, "no-mail", null,
                null, null))).hasMessage("validation.email");
        assertThatThrownBy(() -> customers.create(new CustomerInput("A", null, null, null, "DE", null, null, 366,
                null, null))).hasMessage("validation.paymentDays");
        assertThatThrownBy(() -> customers.create(new CustomerInput("A", null, null, null, "DE", null, null, null,
                10_001, null))).hasMessage("validation.percent");
        assertThatThrownBy(() -> customers.update("unknown", input("A"))).isInstanceOf(RuleViolationException.class);
        assertThat(customers.search("", CustomerStatus.ALL)).isEmpty();
    }

    @Test
    void countryMustBeAKnownIsoCode() {
        assertThat(customers.create(withCountry("at")).country()).isEqualTo("AT");
        assertThatThrownBy(() -> customers.create(withCountry("XX")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.countryUnknown");
    }

    @Test
    void archivedCustomersAreKeptButHiddenFromActiveSelection() {
        var created = customers.create(input("Müller GmbH"));
        var other = customers.create(input("Schmidt AG"));

        customers.archive(created.id());

        assertThat(customers.listActive()).extracting(CustomerInfo::id).containsExactly(other.id());
        assertThat(customers.search("", CustomerStatus.ACTIVE)).hasSize(1);
        assertThat(customers.search("", CustomerStatus.ARCHIVED)).extracting(CustomerInfo::id)
                .containsExactly(created.id());
        assertThat(customers.search("", CustomerStatus.ALL)).hasSize(2);
        assertThat(customers.find(created.id())).get().extracting(CustomerInfo::archived).isEqualTo(true);

        customers.restore(created.id());

        assertThat(customers.listActive()).hasSize(2);
    }

    @Test
    void searchFindsUmlautsCaseInsensitively() {
        customers.create(input("Müller GmbH"));
        customers.create(input("Schmidt AG"));

        assertThat(customers.search("müller", CustomerStatus.ACTIVE)).hasSize(1);
        assertThat(customers.search("MÜLLER", CustomerStatus.ACTIVE)).hasSize(1);
        assertThat(customers.search("kd0002", CustomerStatus.ACTIVE)).extracting(CustomerInfo::name)
                .containsExactly("Schmidt AG");
        assertThat(customers.search("musterstadt", CustomerStatus.ACTIVE)).hasSize(2);
        assertThat(customers.search("nichts", CustomerStatus.ACTIVE)).isEmpty();
    }

    @Test
    void contactKeepsSalutationAndLastNameAndRejectsUnknownSalutation() {
        var customer = customers.create(new CustomerInput("Muster GmbH", null, null, null, "DE", null, null, null, null,
                null));

        var contact = customers.addContact(customer.id(),
                new ContactInput("MR", "Frank", " van Kirsch ", null, null, null, null));

        assertThat(contact.salutation()).isEqualTo("MR");
        assertThat(contact.lastName()).isEqualTo("van Kirsch");
        assertThat(contact.fullName()).isEqualTo("Frank van Kirsch");
        assertThatThrownBy(() -> customers.addContact(customer.id(),
                new ContactInput("XX", "Anna", "Meier", null, null, null, null)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("contact.error.salutation");
    }

    @Test
    void contactsCanBeAddedChangedAndRemoved() {
        var customer = customers.create(input("Müller GmbH"));
        var other = customers.create(input("Schmidt AG"));

        var contact = customers.addContact(customer.id(),
                new ContactInput(null, " Erika ", " Beispiel ", "erika@example.org", " ", null, "Buchhaltung"));
        var changed = customers.updateContact(customer.id(), contact.id(),
                new ContactInput(null, "Erika", "Beispiel", null, "0123 456", null, "Einkauf"));

        assertThat(contact.fullName()).isEqualTo("Erika Beispiel");
        assertThat(contact.phone()).isNull();
        assertThat(contact.mobile()).isNull();
        assertThat(changed.role()).isEqualTo("Einkauf");
        assertThat(customers.listContacts(customer.id())).containsExactly(changed);
        assertThat(customers.listContacts(other.id())).isEmpty();

        assertThatThrownBy(() -> customers.removeContact(other.id(), contact.id()))
                .isInstanceOf(RuleViolationException.class);

        customers.removeContact(customer.id(), contact.id());

        assertThat(customers.listContacts(customer.id())).isEmpty();
    }

    @Test
    void invalidContactIsRejected() {
        var customer = customers.create(input("Müller GmbH"));

        assertThatThrownBy(() -> customers.addContact(customer.id(), new ContactInput("A", " ")))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> customers.addContact(customer.id(), new ContactInput(null, "A", "B", "x", null, null, null)))
                .hasMessage("validation.email");
        assertThatThrownBy(() -> customers.addContact("unknown", new ContactInput("A", "B")))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void personalDataIsNotPartOfToString() {
        var customer = customers.create(new CustomerInput("Müller GmbH", "Hauptstr. 1", "12345", "Musterstadt", "DE",
                "DE123456789", "rechnung@example.org", null, null, "geheim"));
        var contact = customers.addContact(customer.id(),
                new ContactInput(null, "Erika", "Beispiel", "erika@example.org", "0123", null, "Einkauf"));

        var text = customer + " " + contact + " " + input("Müller GmbH") + " "
                + new ContactInput(null, "Erika", "Beispiel", "erika@example.org", "0123", null, "Einkauf");

        assertThat(text).doesNotContain("Müller", "Erika", "example.org", "Hauptstr", "DE123456789", "geheim");
    }

    private static CustomerInput input(String name) {
        return new CustomerInput(name, "Hauptstraße 1", "12345", "Musterstadt", "DE", null, null, null, null, null);
    }

    private static CustomerInput withCountry(String country) {
        return new CustomerInput("Firma", null, null, null, country, null, null, null, null, null);
    }

    private static CustomerInput withVatId(String vatId) {
        return new CustomerInput("Firma", null, null, null, "DE", vatId, null, null, null, null);
    }
}
