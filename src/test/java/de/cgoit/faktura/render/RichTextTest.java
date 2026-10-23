package de.cgoit.faktura.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RichTextTest {

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
                Arguments.of("<b>&\"</b>", "&lt;b&gt;&amp;&quot;&lt;/b&gt;"));
    }

    @ParameterizedTest
    @MethodSource("inlineCases")
    void inlineMarkup(String input, String expected) {
        assertThat(RichText.inline(input)).isEqualTo(expected);
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
                Arguments.of("Kunde Foo \\_ Bar \\* Baz", "Kunde Foo _ Bar * Baz"));
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
