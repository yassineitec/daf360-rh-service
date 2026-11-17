package com.daf360.rh.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonNameFormatterTest {

    @Test
    void lastWordUpperCaseOthersCapitalised() {
        assertEquals("Jean Ben DUPONT", PersonNameFormatter.format("jean ben dupont"));
        assertEquals("Jean DUPONT", PersonNameFormatter.format("jean dupont"));
        assertEquals("Jean DUPONT", PersonNameFormatter.format("JEAN DUPONT"));
    }

    @Test
    void collapsesSpaces() {
        assertEquals("Jean Ben DUPONT", PersonNameFormatter.format("  jean   ben  dupont "));
    }

    @Test
    void hyphensApostrophesAndAccents() {
        assertEquals("Jean-Marc BEN-SALAH", PersonNameFormatter.format("jean-marc ben-salah"));
        assertEquals("Hélène D'ARC", PersonNameFormatter.format("hélène d'arc"));
        assertEquals("Ève LÉGER", PersonNameFormatter.format("ève léger"));
    }

    @Test
    void singleWordIsAFirstName() {
        assertEquals("Jean", PersonNameFormatter.format("jean"));
    }

    @Test
    void composeFromSeparateFields() {
        assertEquals("Jean BEN DUPONT", PersonNameFormatter.compose("jean", "ben dupont"));
        assertEquals("Jean Ben DUPONT", PersonNameFormatter.compose(" jean  ben ", "dupont"));
        assertEquals("Jean-Marc BEN-SALAH", PersonNameFormatter.compose("JEAN-MARC", "ben-salah"));
        assertEquals("DUPONT", PersonNameFormatter.compose("", "dupont"));
        assertEquals("Jean", PersonNameFormatter.compose("jean", null));
        assertNull(PersonNameFormatter.compose(" ", null));
    }

    @Test
    void separateFieldFormatters() {
        assertEquals("Jean-Marc Ben", PersonNameFormatter.formatFirstName("  JEAN-MARC   ben "));
        assertEquals("BEN DUPONT", PersonNameFormatter.formatLastName(" ben  dupont "));
        assertNull(PersonNameFormatter.formatFirstName("  "));
        assertNull(PersonNameFormatter.formatLastName(null));
    }

    @Test
    void blankIsNull() {
        assertNull(PersonNameFormatter.format(null));
        assertNull(PersonNameFormatter.format("   "));
    }
}
