package de.cgoit.faktura.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that the recurring tables are portable, that the unique key on plan and period holds
 * and that the daily job and the run status work. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = { "spring.datasource.driver-class-name=org.postgresql.Driver",
        "faktura.recurring.enabled=false" })
class RecurringPostgresTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void archivePath(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("faktura-archive-pg-recurring");

        registry.add("faktura.archive.path", directory::toString);
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CustomerService customers;

    @Autowired
    private RecurringPlanService plans;

    @Autowired
    private RecurringJobService job;

    @Autowired
    private FinalizeService finalizer;

    @BeforeEach
    void resetData() {
        for (var table : new String[] {"recurring_run", "recurring_plan_item", "recurring_plan", "audit_log", "file",
                "document_tax_total", "document_item", "document", "customer_contact", "customer", "tax_profile",
                "company", "number_sequence"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }

        jdbc.sql("""
                INSERT INTO company (id, name, owner, country, iban, payment_days, created_at)
                VALUES ('c-pg', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'DE', 'DE02120300000000202051', 14,
                        '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, regime, default_rate_bp, default_category,
                                         exemption_text, allowed_categories)
                VALUES ('p-pg', 'c-pg', '2020-01-01', 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG', 'E')
                """).update();
    }

    @Test
    void jobIsIdempotentAndFinalizingReleasesTheRun() {
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, null,
                null, null));
        var plan = plans.create(new PlanInput(customer.id(), "Wartung", RecurringInterval.QUARTER,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null))));

        jdbc.sql("UPDATE recurring_plan SET next_period_index = 0 WHERE id = :id")
                .param("id", plan.id())
                .update();

        assertThat(job.runDue(TODAY)).isEqualTo(1);
        assertThat(job.runDue(TODAY)).isZero();

        var documentId = jdbc.sql("SELECT document_id FROM recurring_run")
                .query(String.class)
                .single();

        finalizer.finalizeInvoice(documentId);

        assertThat(jdbc.sql("SELECT status FROM recurring_run")
                .query(String.class)
                .single()).isEqualTo("RELEASED");
    }

    @Test
    void uniqueKeyAndChecksRejectInvalidRuns() {
        var customer = customers.create(new CustomerInput("Schmidt AG", null, null, null, "DE", null, null, null,
                null, null));
        var plan = plans.create(new PlanInput(customer.id(), "Wartung", RecurringInterval.MONTH,
                LocalDate.parse("2026-11-01"), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Wartung", 1000, "Std", 45_000, 0, null, null))));

        jdbc.sql("""
                INSERT INTO recurring_run (id, plan_id, service_from, service_to, status, created_at)
                VALUES ('r1', :planId, '2026-11-01', '2026-11-30', 'SKIPPED', '2026-10-20T00:00:00Z')
                """).param("planId", plan.id()).update();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO recurring_run (id, plan_id, service_from, service_to, status, created_at)
                VALUES ('r2', :planId, '2026-11-01', '2026-11-30', 'DRAFT', '2026-10-20T00:00:00Z')
                """).param("planId", plan.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO recurring_run (id, plan_id, service_from, service_to, status, created_at)
                VALUES ('r3', :planId, '2026-12-01', '2026-12-31', 'DONE', '2026-10-20T00:00:00Z')
                """).param("planId", plan.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE recurring_plan SET status = 'GONE' WHERE id = :id")
                .param("id", plan.id())
                .update()).isInstanceOf(DataAccessException.class);
    }
}
