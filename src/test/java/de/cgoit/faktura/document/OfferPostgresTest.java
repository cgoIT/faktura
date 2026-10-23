package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that offers (validity date, number range, event table) are portable. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class OfferPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private OfferService offers;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetData() {
        for (var table : new String[] {"app_user", "audit_log", "file", "offer_event", "document_tax_total",
                "document_item", "document", "customer_contact", "customer", "tax_profile", "company",
                "number_sequence"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @Test
    void anOfferIsFinalizedWithItsOwnNumberAndValidityAndEventsAreStored() {
        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, iban, payment_days, offer_valid_days, tax_number,
                                     created_at)
                VALUES ('c-pg', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'DE', 'DE02120300000000202051', 14, 21,
                        '12/345/67890', '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category,
                                         exemption_text, allowed_categories)
                VALUES ('p-pg', 'c-pg', '2020-01-01', 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG', 'E')
                """).update();
        var customer = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null,
                null, null));
        var item = new InvoiceItemInput("Beratung", 1000, "Std", 10_000, 0, "E", 0, null);
        var today = LocalDate.now();
        var draft = invoices.createOfferDraft(new InvoiceInput(customer.id(), today, null, null,
                null, null, null, null, TotalDiscount.none(), List.of(item)));

        var offer = finalizer.finalizeInvoice(draft.id());

        assertThat(offer.number()).isEqualTo("AN" + today.getYear() + "00001");
        assertThat(offer.validUntil()).isEqualTo(today.plusDays(21));
        assertThat(offers.list("", OfferState.OPEN)).hasSize(1);

        jdbc.sql("""
                INSERT INTO offer_event (id, document_id, event, event_date, actor, created_at)
                VALUES ('e1', :id, 'ACCEPTED', '2026-10-25', 'anonymous', '2026-10-25T10:00:00Z')
                """)
                .param("id", offer.id())
                .update();

        assertThat(offers.state(offer.id())).contains(OfferState.ACCEPTED);
        assertThat(offers.events(offer.id())).extracting(OfferEvent::type).containsExactly(OfferEventType.ACCEPTED);
    }
}
