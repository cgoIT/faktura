package de.cgoit.faktura.mail;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.ContactInput;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Invented master data and finalized invoices for the mail tests.
 */
final class MailTestData {

    static final String CUSTOMER_EMAIL = "kunde@example.test";
    static final String CONTACT_EMAIL = "erika.beispiel@example.test";

    private MailTestData() {
    }

    static void company(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, email, iban, bic, bank_name,
                                     payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Max Mustermann', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        'rechnung@example.test', 'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG keine Umsatzsteuer.')
                """).update();
    }

    static void cleanUp(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM mail_log").update();
        jdbc.sql("DELETE FROM offer_event").update();
        jdbc.sql("DELETE FROM mail_template WHERE id NOT LIKE '30000000-%'").update();
        jdbc.sql("UPDATE mail_template SET is_default = 0").update();
        jdbc.sql("UPDATE mail_template SET is_default = 1 WHERE name = 'Formell' OR scope = 'NOTIFICATION_DIGEST'").update();
        jdbc.sql("DELETE FROM dunning").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        TestArchive.clear();
    }

    /** Creates a customer with an invoice address and a contact person with an own address; returns the ids. */
    static String[] customer(CustomerService customers) {
        var customer = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen",
                "DE", null, CUSTOMER_EMAIL, null, null, null));
        var contact = customers.addContact(customer.id(), new ContactInput("MS", "Erika", "Beispiel", CONTACT_EMAIL,
                null, null, null));

        return new String[] {customer.id(), contact.id()};
    }

    /** Finalized invoice of 1,000.00 EUR issued today, due after 14 days. */
    static InvoiceInfo invoice(InvoiceService invoices, FinalizeService finalizer, String customerId, String contactId,
            LocalDate issued) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, issued, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(item), contactId));

        return finalizer.finalizeInvoice(draft.id());
    }

    /** Finds the first part with the content type below the part, searching nested multiparts. */
    static Part find(Part part, String contentType) throws MessagingException, IOException {
        if (part.isMimeType(contentType)) {
            return part;
        }

        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();

            for (var i = 0; i < multipart.getCount(); i++) {
                var found = find(multipart.getBodyPart(i), contentType);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    static BodyPart body(Multipart multipart, int index) throws MessagingException {
        return multipart.getBodyPart(index);
    }
}
