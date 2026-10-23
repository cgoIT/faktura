package de.cgoit.faktura.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactInfoTest {

    private static ContactInfo contact(String firstName, String lastName) {
        return new ContactInfo("c1", "k1", "MS", firstName, lastName, null, null, null, null);
    }

    @Test
    void fullNameJoinsFirstAndLastName() {
        assertThat(contact("Erika", "Beispiel").fullName()).isEqualTo("Erika Beispiel");
        assertThat(contact("Frank", "van Kirsch").fullName()).isEqualTo("Frank van Kirsch");
    }

    @Test
    void fullNameIsTheLastNameWithoutFirstName() {
        assertThat(contact(null, "Beispiel").fullName()).isEqualTo("Beispiel");
        assertThat(contact(" ", "Beispiel").fullName()).isEqualTo("Beispiel");
    }
}
