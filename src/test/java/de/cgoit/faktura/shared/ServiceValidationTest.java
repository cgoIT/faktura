package de.cgoit.faktura.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.dunning.DunningLevelInput;
import de.cgoit.faktura.dunning.DunningLevelService;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateInput;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The services report a violated rule with its message key and the property of the input.
 */
@SqliteTest
class ServiceValidationTest {

    @Autowired
    private UnitService units;

    @Autowired
    private DunningLevelService levels;

    @Autowired
    private TextTemplateService texts;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM unit WHERE code IN ('Wo', 'Lt')").update();
        jdbc.sql("DELETE FROM text_template WHERE name LIKE 'Test%'").update();
    }

    private static void assertViolation(Throwable thrown, String key, String field) {
        assertThat(thrown).isInstanceOf(RuleViolationException.class).hasMessage(key);
        assertThat(((RuleViolationException) thrown).field()).isEqualTo(field);
    }

    @Test
    void unitCodeIsNormalizedAndChecked() {
        var created = units.create(" Wo ", " Woche ", " ", "wee");

        assertThat(created.code()).isEqualTo("Wo");
        assertThat(created.namePlural()).isNull();
        assertThat(created.uneceCode()).isEqualTo("WEE");
        assertThatThrownBy(() -> units.create("Lt", "Liter", null, " "))
                .satisfies(e -> assertViolation(e, "validation.required", "uneceCode"));
        assertThatThrownBy(() -> units.create("Lt", "Liter", null, "L!"))
                .satisfies(e -> assertViolation(e, "unit.error.uneceCode", "uneceCode"));
        assertThatThrownBy(() -> units.create("x".repeat(21), "Lang", null, "H87"))
                .satisfies(e -> assertViolation(e, "validation.tooLong", "code"));
    }

    @Test
    void dunningLevelValuesAreChecked() {
        var level = levels.list().getFirst();

        assertThatThrownBy(() -> levels.update(level.id(), new DunningLevelInput(level.level(), level.name(), 7, 0, 0,
                7, " ", "Fuß", true)))
                .satisfies(e -> assertViolation(e, "validation.required", "headerText"));
        assertThatThrownBy(() -> levels.update(level.id(), new DunningLevelInput(100, level.name(), 7, 0, 0, 7, "Kopf",
                "Fuß", true)))
                .satisfies(e -> assertViolation(e, "dunning.level.error.level", "level"));
        assertThatThrownBy(() -> levels.update(level.id(), new DunningLevelInput(level.level(), level.name(), 7, -1, 0,
                7, "Kopf", "Fuß", true)))
                .satisfies(e -> assertViolation(e, "validation.money", "feeCents"));
        assertThatThrownBy(() -> levels.update(level.id(), new DunningLevelInput(level.level(), level.name(), 7, 0,
                10_001, 7, "Kopf", "Fuß", true)))
                .satisfies(e -> assertViolation(e, "validation.percent", "interestBp"));
    }

    @Test
    void textTemplateValuesAreChecked() {
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, "Test", "x".repeat(4001),
                false)))
                .satisfies(e -> assertViolation(e, "validation.tooLong", "body"));
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, " ", "Text", false)))
                .satisfies(e -> assertViolation(e, "validation.required", "name"));
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(null, TextKind.HEADER, "Test", "Text", false)))
                .satisfies(e -> assertViolation(e, "validation.required", "scope"));
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, "Test",
                "Hallo {gibtsnicht}", false)))
                .hasMessage("text.error.unknownPlaceholder");
    }
}
