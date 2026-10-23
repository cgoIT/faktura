package de.cgoit.faktura.notification;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.TotalDiscount;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Invented master data, invoices and clean-up for the notification tests.
 */
final class NotificationTestData {

    static final String OPERATOR_EMAIL = "betreiber@example.test";
    static final String CUSTOMER_EMAIL = "kunde@example.test";

    private NotificationTestData() {
    }

    static void company(JdbcClient jdbc, String email) {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, email, iban, bic, bank_name,
                                     payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Max Mustermann', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        :email, 'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14, '2026-01-01T00:00:00Z')
                """)
                .param("email", email)
                .update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG keine Umsatzsteuer.')
                """).update();
    }

    static String customer(CustomerService customers, String name) {
        return customers.create(new CustomerInput(name, "Teststraße 1", "54321", "Musterhausen", "DE", null,
                CUSTOMER_EMAIL, null, null, null)).id();
    }

    /** Finalized invoice of 1,000.00 EUR with a payment term of 14 days. */
    static InvoiceInfo invoice(InvoiceService invoices, FinalizeService finalizer, String customerId,
            LocalDate issued) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, issued, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(item)));

        return finalizer.finalizeInvoice(draft.id());
    }

    /** Returns the plain text part of a mail, searching nested multiparts. */
    static String text(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }

        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();

            for (var i = 0; i < multipart.getCount(); i++) {
                var found = text(multipart.getBodyPart(i));

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    static void cleanUp(JdbcClient jdbc) {
        TestArchive.clear();
        jdbc.sql("DELETE FROM notification_log").update();
        jdbc.sql("UPDATE notification_setting SET draft_enabled = 0, plan_error_enabled = 0, overdue_enabled = 0, "
                + "dunning_enabled = 0, offer_enabled = 0, recipient = NULL, send_time = NULL, last_run_date = NULL, "
                + "failure_count = 0")
                .update();
        jdbc.sql("DELETE FROM recurring_run").update();
        jdbc.sql("DELETE FROM recurring_plan_item").update();
        jdbc.sql("DELETE FROM recurring_plan").update();
        jdbc.sql("DELETE FROM mail_log").update();
        jdbc.sql("DELETE FROM offer_event").update();
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
    }
}
