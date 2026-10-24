package de.cgoit.faktura.customer;

import static de.cgoit.faktura.shared.Texts.blankToNull;
import static de.cgoit.faktura.shared.Texts.contains;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import de.cgoit.faktura.customer.internal.ContactRepository;
import de.cgoit.faktura.customer.internal.CustomerRepository;
import de.cgoit.faktura.numbering.NumberScope;
import de.cgoit.faktura.numbering.NumberService;
import de.cgoit.faktura.shared.Countries;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains customers and their contact persons. Customers are never deleted, only archived, because documents
 * refer to them.
 */
@Service
public class CustomerService {

    private static final Pattern GERMAN_VAT_ID = Pattern.compile("DE\\d{9}");
    private static final Pattern VAT_ID = Pattern.compile("[A-Z]{2}[A-Z0-9]{2,12}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final NumberService numbers;
    private final InputValidator validator;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, ContactRepository contacts, NumberService numbers,
            InputValidator validator, Clock clock) {
        this.customers = customers;
        this.contacts = contacts;
        this.numbers = numbers;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Lists customers whose number, name, city or invoice e-mail contains the search text (case-insensitive,
     * German rules), ordered by name.
     *
     * @param query  search text, blank for all
     * @param status which customers to include
     */
    @Transactional(readOnly = true)
    public List<CustomerInfo> search(String query, CustomerStatus status) {
        var needle = query == null ? "" : query.strip().toLowerCase(Locale.GERMAN);
        var archived = switch (status) {
            case ACTIVE -> Boolean.FALSE;
            case ARCHIVED -> Boolean.TRUE;
            case ALL -> null;
        };

        var all = customers.findAll(archived);

        return all.stream()
                .filter(c -> needle.isEmpty()
                        || contains(c.number(), needle)
                        || contains(c.name(), needle)
                        || contains(c.city(), needle)
                        || contains(c.invoiceEmail(), needle))
                .toList();
    }

    /**
     * Lists the customers that can be chosen for new documents (not archived).
     */
    @Transactional(readOnly = true)
    public List<CustomerInfo> listActive() {
        return customers.findAll(false);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerInfo> find(String id) {
        return customers.findById(id);
    }

    /**
     * Creates a customer and assigns the next customer number in the same transaction, so a failed save leaves
     * the number free.
     *
     * @throws RuleViolationException if the input is invalid
     */
    @Transactional
    public CustomerInfo create(CustomerInput input) {
        var valid = validated(input);
        var id = UUID.randomUUID().toString();
        var number = numbers.next(NumberScope.CUSTOMER, LocalDate.now(clock));
        customers.insert(id, number, valid);

        return customers.findById(id).orElseThrow();
    }

    /**
     * Changes a customer. The number stays; finalized documents keep their own copy of the customer data.
     *
     * @throws RuleViolationException if the customer does not exist or the input is invalid
     */
    @Transactional
    public CustomerInfo update(String id, CustomerInput input) {
        requireExisting(id);
        customers.update(id, validated(input));

        return customers.findById(id).orElseThrow();
    }

    /**
     * Archives a customer: it disappears from selections and the default list but is kept.
     */
    @Transactional
    public void archive(String id) {
        requireExisting(id);
        customers.setArchived(id, true);
    }

    @Transactional
    public void restore(String id) {
        requireExisting(id);
        customers.setArchived(id, false);
    }

    @Transactional(readOnly = true)
    public Optional<ContactInfo> findContact(String contactId) {
        return contacts.findById(contactId);
    }

    public List<ContactInfo> listContacts(String customerId) {
        return contacts.findByCustomer(customerId);
    }

    /**
     * Adds a contact person to a customer.
     *
     * @throws RuleViolationException if the customer does not exist or the input is invalid
     */
    @Transactional
    public ContactInfo addContact(String customerId, ContactInput input) {
        requireExisting(customerId);

        var valid = validatedContact(input);
        var id = UUID.randomUUID().toString();
        contacts.insert(id, customerId, valid);

        return contacts.findById(id).orElseThrow();
    }

    /**
     * Changes a contact person of the given customer.
     *
     * @throws RuleViolationException if the contact does not belong to the customer or the input is invalid
     */
    @Transactional
    public ContactInfo updateContact(String customerId, String contactId, ContactInput input) {
        requireContact(customerId, contactId);
        contacts.update(contactId, validatedContact(input));

        return contacts.findById(contactId).orElseThrow();
    }

    @Transactional
    public void removeContact(String customerId, String contactId) {
        requireContact(customerId, contactId);
        contacts.delete(contactId);
    }

    private void requireExisting(String id) {
        customers.findById(id)
                .orElseThrow(() -> new RuleViolationException("customer.error.unknown"));
    }

    private void requireContact(String customerId, String contactId) {
        contacts.findById(contactId)
                .filter(contact -> contact.customerId().equals(customerId))
                .orElseThrow(() -> new RuleViolationException("customer.error.unknownContact"));
    }

    /**
     * Normalizes the input (stripped values, blank as empty, country default, compact upper-case VAT ID) and checks it
     * against the annotations of {@link CustomerInput}. The rules that depend on the content (known country, VAT ID
     * format, e-mail format, supported e-invoice kinds) follow.
     */
    private CustomerInput validated(CustomerInput input) {
        var givenCountry = blankToNull(input.country());
        var normalized = new CustomerInput(blankToNull(input.name()), blankToNull(input.street()),
                blankToNull(input.zip()), blankToNull(input.city()),
                givenCountry == null ? "DE" : givenCountry.toUpperCase(Locale.ROOT), compactVatId(input.vatId()),
                blankToNull(input.invoiceEmail()), input.paymentDays(), input.discountBp(), blankToNull(input.note()),
                input.einvoiceMode() == null ? EInvoiceMode.NONE : input.einvoiceMode());

        validator.check(normalized);
        requireKnownCountry(normalized.country());
        requireVatIdFormat(normalized.vatId());
        requireEmailFormat(normalized.invoiceEmail(), "invoiceEmail");

        if (normalized.einvoiceMode() == EInvoiceMode.XRECHNUNG) {
            throw new RuleViolationException("customer.error.einvoiceMode", "einvoiceMode");
        }

        return normalized;
    }

    private ContactInput validatedContact(ContactInput input) {
        var normalized = new ContactInput(blankToNull(input.salutation()), blankToNull(input.firstName()),
                blankToNull(input.lastName()), blankToNull(input.email()), blankToNull(input.phone()),
                blankToNull(input.mobile()), blankToNull(input.role()));

        validator.check(normalized);
        requireEmailFormat(normalized.email(), "email");

        return normalized;
    }

    /**
     * Removes spaces and upper-cases the VAT ID; empty becomes {@code null}.
     */
    private static String compactVatId(String vatId) {
        var compact = vatId == null ? "" : vatId.replaceAll("\\s", "").toUpperCase(Locale.ROOT);

        return compact.isEmpty() ? null : compact;
    }

    private static void requireKnownCountry(String country) {
        if (!Countries.isKnown(country)) {
            throw new RuleViolationException("validation.countryUnknown", "country");
        }
    }

    /**
     * Checks the format only (no online validation): German IDs are {@code DE} plus nine digits, other countries two
     * letters plus 2 to 12 letters or digits.
     */
    private static void requireVatIdFormat(String vatId) {
        if (vatId == null) {
            return;
        }

        var pattern = vatId.startsWith("DE") ? GERMAN_VAT_ID : VAT_ID;

        if (!pattern.matcher(vatId).matches()) {
            throw new RuleViolationException("validation.vatId", "vatId");
        }
    }

    private static void requireEmailFormat(String email, String field) {
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw new RuleViolationException("validation.email", field);
        }
    }
}
