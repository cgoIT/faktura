package de.cgoit.faktura.textblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class TextTemplateServiceTest {

    private static final String SEEDED_HEADER = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private TextTemplateService texts;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void removeTestTemplates() {
        jdbc.sql("DELETE FROM text_template WHERE id NOT LIKE '10000000-%'").update();
        jdbc.sql("UPDATE text_template SET is_default = 1 WHERE id LIKE '10000000-%'").update();
    }

    @Test
    void seededDefaultsExistForInvoices() {
        assertThat(texts.defaultBody(TextScope.INVOICE, TextKind.HEADER)).hasValueSatisfying(body ->
                assertThat(body).contains("{anrede}", "in Rechnung zu stellen"));
        assertThat(texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER)).hasValueSatisfying(body ->
                assertThat(body).contains("{firma_iban}", "{betrag}", "{faelligkeit}", "Liefer-/Leistungsdatum"));
        assertThat(texts.defaultBody(TextScope.DUNNING, TextKind.HEADER)).isEmpty();
    }

    @Test
    void newDefaultReplacesTheOldOneOfTheSameScopeAndKind() {
        var created = texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, "Kurz",
                "{anrede}\n\nanbei die Rechnung:", true));

        assertThat(texts.defaultBody(TextScope.INVOICE, TextKind.HEADER)).contains("{anrede}\n\nanbei die Rechnung:");
        assertThat(texts.find(SEEDED_HEADER).orElseThrow().isDefault()).isFalse();
        assertThat(texts.find(created.id()).orElseThrow().isDefault()).isTrue();
        assertThat(texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER)).isPresent();
    }

    @Test
    void updateKeepsScopeAndKindAndTheDefaultFlag() {
        var created = texts.create(new TextTemplateInput(TextScope.DUNNING, TextKind.HEADER, "Alt", "Alter Text", true));

        var updated = texts.update(created.id(), new TextTemplateInput(TextScope.OFFER, TextKind.FOOTER, "Neu",
                "Neuer Text", false));

        assertThat(updated.scope()).isEqualTo(TextScope.DUNNING);
        assertThat(updated.kind()).isEqualTo(TextKind.HEADER);
        assertThat(updated.name()).isEqualTo("Neu");
        assertThat(updated.isDefault()).isTrue();
    }

    @Test
    void unknownPlaceholdersAndMissingValuesAreRejected() {
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, "X",
                "Hallo {vorname}", false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("text.error.unknownPlaceholder");
        assertThatThrownBy(() -> texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, " ", "x",
                false)))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> texts.expand("{kunde} {unbekannt}", Map.of()))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void expandFillsPlaceholdersAndLeavesMissingValuesEmpty() {
        var text = texts.expand("Rechnung {rechnungsnummer} für {kunde} ({leistungszeitraum}), {anrede} $1 \\",
                Map.of(Placeholder.NUMBER, "RE202700001", Placeholder.CUSTOMER, "Muster & Söhne",
                        Placeholder.SALUTATION, "Guten Tag,"));

        assertThat(text).isEqualTo("Rechnung RE202700001 für Muster & Söhne (), Guten Tag, $1 \\");
        assertThat(texts.expand(null, Map.of())).isNull();
        assertThat(texts.expand("Text mit { geschweiften } Klammern", Map.of())).isEqualTo("Text mit { geschweiften } Klammern");
    }

    @Test
    void salutationFollowsTheContactData() {
        assertThat(texts.salutation("MR", "Kirsch", "Frank Kirsch")).isEqualTo("Sehr geehrter Herr Kirsch,");
        assertThat(texts.salutation("MS", null, "Anna van der Berg")).isEqualTo("Sehr geehrte Frau Berg,");
        assertThat(texts.salutation("MS", "van der Berg", "Anna van der Berg"))
                .isEqualTo("Sehr geehrte Frau van der Berg,");
        assertThat(texts.salutation(null, null, "Frank Kirsch")).isEqualTo("Guten Tag Frank Kirsch,");
        assertThat(texts.salutation(null, null, null)).isEqualTo("Sehr geehrte Damen und Herren,");
    }

    @Test
    void deletedDefaultLeavesNoDefaultAndCannotBeDeletedTwice() {
        var created = texts.create(new TextTemplateInput(TextScope.OFFER, TextKind.HEADER, "Angebot", "Text", true));

        texts.delete(created.id());

        assertThat(texts.defaultBody(TextScope.OFFER, TextKind.HEADER)).isEmpty();
        assertThatThrownBy(() -> texts.delete(created.id())).isInstanceOf(RuleViolationException.class);
    }
}
