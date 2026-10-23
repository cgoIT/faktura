package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SqliteTest
class PaymentPagesTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    private String customerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.sql("""
                INSERT INTO company (id, name, country, payment_days, created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'DE', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp, default_category)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E')
                """).update();
        customerId = customers.create(new CustomerInput("Müller GmbH", null, null, null, "DE", null, null, null, null,
                null)).id();
    }

    @AfterEach
    void cleanUp() {
        TestArchive.clear();
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

    private InvoiceInfo invoice(int daysAgo) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY.minusDays(daysAgo), null, null, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.none(), List.of(item)));

        return finalizer.finalizeInvoice(draft.id());
    }

    @Test
    void amountIsPrefilledWithTheOpenAmount() throws Exception {
        var invoice = invoice(1);

        mvc.perform(get("/invoices/" + invoice.id()))
                .andExpect(content().string(containsString("value=\"1000,00\"")));
    }

    @Test
    void invoicePageOffersPaymentFormAndShowsTheState() throws Exception {
        var invoice = invoice(30);

        mvc.perform(get("/invoices/" + invoice.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zahlung erfassen")))
                .andExpect(content().string(containsString("Überfällig seit 16 Tagen")))
                .andExpect(content().string(containsString("1.000,00")));
    }

    @Test
    void recordingAndReversingAPaymentReturnsThePanel() throws Exception {
        var invoice = invoice(1);

        mvc.perform(post("/invoices/" + invoice.id() + "/payments").header("HX-Request", "true")
                        .param("date", TODAY.toString()).param("amount", "400,00").param("method", "BANK_TRANSFER")
                        .param("reference", "Zahlung"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Die Zahlung wurde erfasst.")))
                .andExpect(content().string(containsString("Teilbezahlt")))
                .andExpect(content().string(containsString("600,00")))
                .andExpect(content().string(containsString("value=\"600,00\"")));

        var paymentId = jdbc.sql("SELECT id FROM payment").query(String.class).single();

        mvc.perform(post("/invoices/" + invoice.id() + "/payments/" + paymentId + "/reverse").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Gegenbuchung")))
                .andExpect(content().string(containsString("korrigiert")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void invalidPaymentShowsTheErrorAndStoresNothing() throws Exception {
        var invoice = invoice(1);

        mvc.perform(post("/invoices/" + invoice.id() + "/payments").header("HX-Request", "true")
                        .param("date", TODAY.toString()).param("amount", "abc").param("method", "CASH"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"alert\"")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment").query(Long.class).single()).isZero();
    }

    @Test
    void withoutHtmxThePageIsShownAgain() throws Exception {
        var invoice = invoice(1);

        mvc.perform(post("/invoices/" + invoice.id() + "/payments").param("date", TODAY.toString())
                        .param("amount", "1000").param("method", "CASH"))
                .andExpect(redirectedUrl("/invoices/" + invoice.id()));
    }

    @Test
    void listFiltersByPaymentSituation() throws Exception {
        var overdue = invoice(30);
        var paid = invoice(5);
        var open = invoice(0);

        mvc.perform(post("/invoices/" + paid.id() + "/payments").param("date", TODAY.toString())
                .param("amount", "1000").param("method", "CASH"));

        var overdueList = mvc.perform(get("/invoices").param("status", "overdue"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var paidList = mvc.perform(get("/invoices").param("status", "paid"))
                .andReturn().getResponse().getContentAsString();
        var openList = mvc.perform(get("/invoices").param("status", "open"))
                .andReturn().getResponse().getContentAsString();

        assertThat(overdueList).contains(overdue.number()).doesNotContain(paid.number()).doesNotContain(open.number());
        assertThat(paidList).contains(paid.number()).doesNotContain(overdue.number());
        assertThat(openList).contains(overdue.number()).contains(open.number()).doesNotContain(paid.number());
    }

    @Test
    void draftPageHasNoPaymentPanel() throws Exception {
        var item = new InvoiceItemInput("Beratung", 1_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(item)));

        mvc.perform(get("/invoices/" + draft.id()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Zahlung erfassen"))));
    }
}
