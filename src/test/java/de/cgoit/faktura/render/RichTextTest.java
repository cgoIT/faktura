package de.cgoit.faktura.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RichTextTest {

    @ParameterizedTest
    @MethodSource("inlineCases")
    void inlineMarkup(String input, String expected) {
        assertThat(RichText.inline(input)).isEqualTo(expected);
    }

    static Stream<Arguments> inlineCases() {
        return Stream.of(
                Arguments.of("**fett**", "<b>fett</b>"),
                Arguments.of("*kursiv*", "<i>kursiv</i>"),
                Arguments.of("__unterstrichen__", "<u>unterstrichen</u>"),
                Arguments.of("a **b** c *d* e __f__", "a <b>b</b> c <i>d</i> e <u>f</u>"),
                Arguments.of("**__beides__**", "<b><u>beides</u></b>"),
                Arguments.of("__**beides**__", "<u><b>beides</b></u>"),
                Arguments.of("***fett kursiv***", "<b><i>fett kursiv</i></b>"),
                Arguments.of("ohne Ende **offen", "ohne Ende **offen"),
                Arguments.of("2 * 3 * 4", "2 * 3 * 4"),
                Arguments.of("**a __b** c__", "**a <u>b** c</u>"),
                Arguments.of("\\*kein kursiv\\*", "*kein kursiv*"),
                Arguments.of("snake_case und __x__", "snake_case und <u>x</u>"),
                Arguments.of("<b>&\"</b>", "&lt;b&gt;&amp;&quot;&lt;/b&gt;"),
                Arguments.of("Siehe [unsere Seite](https://example.org/a_b?x=1&y=2).",
                        "Siehe <a href=\"https://example.org/a_b?x=1&amp;y=2\">unsere Seite</a>."),
                Arguments.of("[**fett**](mailto:info@example.org)",
                        "<a href=\"mailto:info@example.org\"><b>fett</b></a>"),
                Arguments.of("[" + RichText.escape("a](https://evil.example) [b") + "](https://example.org)",
                        "<a href=\"https://example.org\">a](https://evil.example) [b</a>"),
                Arguments.of("[x](https://example.org/" + RichText.escape("a_b*c") + ")",
                        "<a href=\"https://example.org/a_b*c\">x</a>"),
                Arguments.of("[x](javascript:alert(1))", "[x](javascript:alert(1))"),
                Arguments.of("\\[x](https://example.org)", "[x](https://example.org)"),
                Arguments.of(RichText.escape("[x](https://example.org)"), "[x](https://example.org)"));
    }

    @Test
    void paragraphsAreSeparatedByBlankLinesAndLinesBreak() {
        assertThat(RichText.toXhtml("Zeile 1\nZeile 2\n\nNeuer Absatz"))
                .isEqualTo("<p>Zeile 1<br/>Zeile 2</p><p>Neuer Absatz</p>");
    }

    @Test
    void bulletAndNumberedListsWithNesting() {
        assertThat(RichText.toXhtml("Vorab:\n- eins\n- zwei\n  - zwei a\n- drei\n\n1. erstens\n2. zweitens"))
                .isEqualTo("<p>Vorab:</p><ul><li>eins</li><li>zwei</li><ul><li>zwei a</li></ul><li>drei</li></ul>"
                        + "<ol><li>erstens</li><li>zweitens</li></ol>");
    }

    @Test
    void listTypeChangeClosesTheOldList() {
        assertThat(RichText.toXhtml("- a\n1. b")).isEqualTo("<ul><li>a</li></ul><ol><li>b</li></ol>");
    }

    @Test
    void leadingSpacesIndentNormalLines() {
        assertThat(RichText.toXhtml("Text\n    eingerückt"))
                .isEqualTo("<p>Text<br/>    eingerückt</p>");
    }

    @Test
    void boldAtLineStartIsNotAListItem() {
        assertThat(RichText.toXhtml("**Wichtig** ist das")).isEqualTo("<p><b>Wichtig</b> ist das</p>");
    }

    @Test
    void blankAndNullGiveNothingAndEscapeKeepsValuesLiteral() {
        assertThat(RichText.toXhtml(null)).isEmpty();
        assertThat(RichText.toXhtml("  \n ")).isEmpty();
        assertThat(RichText.toXhtml(RichText.escape("Müller *& Söhne_ \\ GmbH")))
                .isEqualTo("<p>Müller *&amp; Söhne_ \\ GmbH</p>");
    }

    static Stream<Arguments> plainCases() {
        return Stream.of(
                Arguments.of("a **b** c *d* e __f__", "a b c d e f"),
                Arguments.of("\\*kein kursiv\\*", "*kein kursiv*"),
                Arguments.of("ohne Ende **offen", "ohne Ende **offen"),
                Arguments.of("<b>&\"</b>", "<b>&\"</b>"),
                Arguments.of("Kunde Foo \\_ Bar \\* Baz", "Kunde Foo _ Bar * Baz"),
                Arguments.of("Siehe [unsere Seite](https://example.org).", "Siehe unsere Seite (https://example.org)."),
                Arguments.of("[https://example.org](https://example.org)", "https://example.org"),
                Arguments.of("[Mail](mailto:info@example.org)", "Mail (mailto:info@example.org)"),
                Arguments.of("\\[x](https://example.org)", "[x](https://example.org)"));
    }

    @ParameterizedTest
    @MethodSource("plainCases")
    void plainInlineRemovesMarkersAndKeepsEverythingElse(String input, String expected) {
        assertThat(RichText.plainInline(input)).isEqualTo(expected);
    }

    @Test
    void plainTextKeepsParagraphsListsAndIndentation() {
        assertThat(RichText.toPlainText("Hallo **Welt**\nzweite Zeile\n\n\n- eins\n  - zwei\n1. erst\n\n  eingerückt"))
                .isEqualTo("Hallo Welt\nzweite Zeile\n\n- eins\n  - zwei\n1. erst\n\n  eingerückt");
    }

    @Test
    void plainTextOfBlankInputIsEmpty() {
        assertThat(RichText.toPlainText(null)).isEmpty();
        assertThat(RichText.toPlainText("  \n ")).isEmpty();
    }
}
