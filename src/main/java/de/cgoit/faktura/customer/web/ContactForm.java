package de.cgoit.faktura.customer.web;

import de.cgoit.faktura.customer.ContactInfo;
import de.cgoit.faktura.customer.ContactInput;

/**
 * Form data of a contact person; rules are checked by the service.
 */
public record ContactForm(String salutation, String firstName, String lastName, String email, String phone,
        String mobile, String role) {

    static ContactForm of(ContactInfo contact) {
        return new ContactForm(contact.salutation(), contact.firstName(), contact.lastName(), contact.email(),
                contact.phone(), contact.mobile(), contact.role());
    }

    ContactInput toInput() {
        return new ContactInput(salutation, firstName, lastName, email, phone, mobile, role);
    }

    /**
     * Hides the personal data from logs.
     */
    @Override
    public String toString() {
        return "ContactForm[...]";
    }
}
