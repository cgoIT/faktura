package de.cgoit.faktura.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class UnitServiceTest {

    @Autowired
    private UnitService units;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeTestUnits() {
        jdbc.sql("DELETE FROM catalog_item").update();
        jdbc.sql("DELETE FROM unit WHERE code NOT IN ('Std', 'Stk', 'Mon', 'Psch', 'Tag')").update();
    }

    @Test
    void listsSeededUnits() {
        assertThat(units.list()).extracting(UnitInfo::code)
                .contains("Std", "Stk", "Mon", "Psch", "Tag");
        assertThat(units.list()).filteredOn(unit -> unit.code().equals("Std"))
                .extracting(UnitInfo::namePlural)
                .containsExactly("Stunden");
    }

    @Test
    void createsAndChangesUnit() {
        var created = units.create("Wo", "Woche", "Wochen", "wk");

        var changed = units.update(created.id(), "Wo", "Kalenderwoche", "", "WEE");

        assertThat(created.uneceCode()).isEqualTo("WK");
        assertThat(created.namePlural()).isEqualTo("Wochen");
        assertThat(changed.name()).isEqualTo("Kalenderwoche");
        assertThat(changed.namePlural()).isNull();
        assertThat(units.list()).contains(changed);
    }

    @Test
    void duplicateCodeIsRejected() {
        assertThatThrownBy(() -> units.create("Std", "Stunde 2", null, "HUR"))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void unitInUseCannotBeDeleted() {
        var unit = units.create("Wo", "Woche", null, "WEE");
        jdbc.sql("""
                        INSERT INTO catalog_item (id, number, name, unit_id, unit_price_cents)
                        VALUES ('i1', 'ART0001', 'Beispiel', :unitId, 1000)
                        """)
                .param("unitId", unit.id())
                .update();

        assertThatThrownBy(() -> units.delete(unit.id())).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void unusedUnitCanBeDeleted() {
        var unit = units.create("Wo", "Woche", null, "WEE");

        units.delete(unit.id());

        assertThat(units.list()).extracting(UnitInfo::code).doesNotContain("Wo");
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> units.create(" ", "Woche", null, "WEE")).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> units.create("Wo", "Woche", null, "TOOLONG")).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> units.create("x".repeat(21), "Woche", null, "WEE"))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> units.update("unknown", "Wo", "Woche", null, "WEE"))
                .isInstanceOf(RuleViolationException.class);
    }
}
