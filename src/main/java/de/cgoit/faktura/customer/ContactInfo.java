package de.cgoit.faktura.customer;

/**
 * Contact person of a customer.
 *
 * @param salutation {@code MR}, {@code MS} or {@code null} for none; used for the salutation line of documents
 * @param firstName  first name, always set for contacts saved by the service; may be empty for contacts written by other tools
 */
public record ContactInfo(String id, String customerId, String salutation, String firstName, String lastName,
        String email, String phone, String mobile, String role) {

    /**
     * Returns first and last name separated by a space.
     */
    public String fullName() {
        return firstName == null || firstName.isBlank() ? lastName : firstName + " " + lastName;
    }

    /**
     * Hides the personal data from logs.
     */
    @Override
    public String toString() {
        return "ContactInfo[id=" + id + "]";
    }
}
