package de.cgoit.faktura.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Editable values of a contact person. First and last name are required (a contact is always a person).
 *
 * @param salutation {@code MR}, {@code MS} or blank for none
 */
public record ContactInput(
        @Pattern(regexp = "MR|MS", message = "{contact.error.salutation}") String salutation,
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String firstName,
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String lastName,
        @Size(max = 200, message = "{validation.tooLong}") String email,
        @Size(max = 50, message = "{validation.tooLong}") String phone,
        @Size(max = 50, message = "{validation.tooLong}") String mobile,
        @Size(max = 100, message = "{validation.tooLong}") String role) {

    /**
     * Creates a contact with names only.
     */
    public ContactInput(String firstName, String lastName) {
        this(null, firstName, lastName, null, null, null, null);
    }

    /**
     * Hides the personal data from logs.
     */
    @Override
    public String toString() {
        return "ContactInput[...]";
    }
}
