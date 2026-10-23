package de.cgoit.faktura.customer;

import static org.assertj.core.api.Assertions.assertThat;

import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies on PostgreSQL that customer and catalog persistence is portable. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.driver-class-name=org.postgresql.Driver")
class CustomerCatalogPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private CustomerService customers;

    @Autowired
    private CatalogService catalog;

    @Test
    void customerLifecycleWorks() {
        var created = customers.create(new CustomerInput("Müller GmbH", null, null, "Musterstadt", "DE", "DE123456789",
                null, 14, 550, null));
        customers.addContact(created.id(), new ContactInput("Erika", "Beispiel"));
        customers.archive(created.id());

        assertThat(customers.search("müller", CustomerStatus.ARCHIVED)).extracting(CustomerInfo::id)
                .containsExactly(created.id());
        assertThat(customers.find(created.id()).orElseThrow().discountBp()).isEqualTo(550);
        assertThat(customers.listActive()).isEmpty();
        assertThat(customers.listContacts(created.id())).hasSize(1);
    }

    @Test
    void catalogLifecycleWorks() {
        var created = catalog.create(new CatalogItemInput("Webentwicklung", null,
                "00000000-0000-0000-0000-000000000001", 8500, null));
        catalog.deactivate(created.id());

        assertThat(created.unitPriceCents()).isEqualTo(8500);
        assertThat(catalog.listActive()).isEmpty();
        assertThat(catalog.search("web", false)).hasSize(1);
    }
}
