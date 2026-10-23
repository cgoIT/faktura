package de.cgoit.faktura.numbering;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NumberPatternTest {

    @Test
    void formatsYearAndCounter() {
        NumberPattern p = NumberPattern.parse("RE{jahr}{#####}");
        assertEquals("RE202600001", p.format(2026, 1));
        assertEquals("RE202600254", p.format(2026, 254));
        assertTrue(p.resetsYearly());
    }

    @Test
    void patternWithoutYearNeverResets() {
        NumberPattern p = NumberPattern.parse("KD{####}");
        assertEquals("KD0021", p.format(2027, 21));
        assertFalse(p.resetsYearly());
    }

    @Test
    void counterGrowsBeyondWidth() {
        assertEquals("123456-2027", NumberPattern.parse("{#}-{jahr}").format(2027, 123456));
    }

    @Test
    void rejectsInvalidPatterns() {
        for (String bad : new String[] {"RE{jahr}", "RE{#}{#}", "RE{x}{#}", "RE{jahr{#}", "", "{jahr}{jahr}{#}"}) {
            assertThrows(IllegalArgumentException.class, () -> NumberPattern.parse(bad), bad);
        }
    }
}
