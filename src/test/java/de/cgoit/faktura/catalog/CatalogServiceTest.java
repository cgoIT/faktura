package de.cgoit.faktura.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class CatalogServiceTest {

    private static final String HOUR = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM number_sequence WHERE scope = 'CATALOG'").update();
    }

    @Test
    void createAssignsConsecutiveNumbersWithoutYear() {
        var first = catalog.create(input("Webentwicklung", 8500, null));
        var second = catalog.create(input("Beratung", 9500, "E"));

        assertThat(first.number()).isEqualTo("ART0001");
        assertThat(second.number()).isEqualTo("ART0002");
        assertThat(first.unitCode()).isEqualTo("Std");
        assertThat(first.unitPriceCents()).isEqualTo(8500);
        assertThat(first.active()).isTrue();
        assertThat(second.taxCategory()).isEqualTo("E");
    }

    @Test
    void failedSaveKeepsNumberFree() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            catalog.create(input("Webentwicklung", 8500, null));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(catalog.create(input("Beratung", 9500, null)).number()).isEqualTo("ART0001");
    }

    @Test
    void updateChangesValuesAndKeepsNumber() {
        var created = catalog.create(input("Webentwicklung", 8500, null));

        var changed = catalog.update(created.id(), new CatalogItemInput(" Entwicklung ", " ", HOUR, 9000, "s"));

        assertThat(changed.number()).isEqualTo("ART0001");
        assertThat(changed.name()).isEqualTo("Entwicklung");
        assertThat(changed.description()).isNull();
        assertThat(changed.unitPriceCents()).isEqualTo(9000);
        assertThat(changed.taxCategory()).isEqualTo("S");
    }

    @Test
    void deactivatedEntriesAreHiddenFromActiveListButNotDeleted() {
        var created = catalog.create(input("Webentwicklung", 8500, null));

        catalog.deactivate(created.id());

        assertThat(catalog.listActive()).isEmpty();
        assertThat(catalog.search("", false)).extracting(CatalogItemInfo::id).containsExactly(created.id());

        catalog.activate(created.id());

        assertThat(catalog.listActive()).hasSize(1);
    }

    @Test
    void searchPutsNameMatchesBeforeDescriptionMatches() {
        catalog.create(new CatalogItemInput("Wartung", "Wartung der Website", HOUR, 4900, null));
        catalog.create(new CatalogItemInput("Hosting", "Webhosting je Monat", HOUR, 1500, null));
        catalog.create(new CatalogItemInput("Relaunch Webseite", null, HOUR, 72000, null));
        catalog.create(new CatalogItemInput("Webentwicklung", null, HOUR, 8500, null));

        assertThat(catalog.search("web", true)).extracting(CatalogItemInfo::name)
                .containsExactly("Webentwicklung", "Relaunch Webseite", "Wartung", "Hosting");
        assertThat(catalog.search("", true)).extracting(CatalogItemInfo::name)
                .containsExactly("Wartung", "Hosting", "Relaunch Webseite", "Webentwicklung");
    }

    @Test
    void searchCanOrderAlphabeticallyWithGermanRules() {
        catalog.create(new CatalogItemInput("Zeitung", null, HOUR, 100, null));
        catalog.create(new CatalogItemInput("Überstunden", null, HOUR, 100, null));
        catalog.create(new CatalogItemInput("Beratung", null, HOUR, 100, null));
        catalog.create(new CatalogItemInput("Ärgerzuschlag", null, HOUR, 100, null));

        assertThat(catalog.search("", true, CatalogOrder.NAME)).extracting(CatalogItemInfo::name)
                .containsExactly("Ärgerzuschlag", "Beratung", "Überstunden", "Zeitung");
        assertThat(catalog.search("", true)).extracting(CatalogItemInfo::name)
                .containsExactly("Zeitung", "Überstunden", "Beratung", "Ärgerzuschlag");
    }

    @Test
    void alphabeticalSearchKeepsNameMatchesFirst() {
        catalog.create(new CatalogItemInput("Alpha", "Hosting im Rechenzentrum", HOUR, 100, null));
        catalog.create(new CatalogItemInput("Zeta Hosting", null, HOUR, 100, null));
        catalog.create(new CatalogItemInput("Beta", "Auch Hosting", HOUR, 100, null));

        assertThat(catalog.search("hosting", true, CatalogOrder.NAME)).extracting(CatalogItemInfo::name)
                .containsExactly("Zeta Hosting", "Alpha", "Beta");
    }

    @Test
    void searchFindsUmlautsCaseInsensitively() {
        catalog.create(input("Überstunden", 8500, null));
        catalog.create(input("Beratung", 9500, null));

        assertThat(catalog.search("überst", true)).extracting(CatalogItemInfo::name).containsExactly("Überstunden");
        assertThat(catalog.search("ÜBERST", true)).hasSize(1);
        assertThat(catalog.search("art0002", true)).extracting(CatalogItemInfo::name).containsExactly("Beratung");
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> catalog.create(input(" ", 100, null))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> catalog.create(input("x".repeat(201), 100, null)))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> catalog.create(input("A", -1, null))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> catalog.create(input("A", 100, "X"))).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> catalog.create(new CatalogItemInput("A", null, "unknown", 100, null)))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> catalog.update("unknown", input("A", 100, null)))
                .isInstanceOf(RuleViolationException.class);
        assertThat(catalog.search("", false)).isEmpty();
    }

    private static CatalogItemInput input(String name, long cents, String category) {
        return new CatalogItemInput(name, "Beschreibung", HOUR, cents, category);
    }
}
