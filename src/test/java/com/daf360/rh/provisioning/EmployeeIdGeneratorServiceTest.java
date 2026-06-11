package com.daf360.rh.provisioning;

import com.daf360.rh.service.EmployeeIdGeneratorService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the new matricule generation rule:
 *   [3 letters lastName][3 letters firstName][userId]
 *
 * No Spring context needed — pure string logic.
 */
class EmployeeIdGeneratorServiceTest {

    private final EmployeeIdGeneratorService service = new EmployeeIdGeneratorService();

    // ── Specification examples ────────────────────────────────────────────────

    @Test
    void generate_dupontPierre_returnsDUPPIE125() {
        assertThat(service.generate("Dupont", "Pierre", 125L))
                .isEqualTo("DUPPIE125");
    }

    @Test
    void generate_martinSara_returnsMARSAR78() {
        assertThat(service.generate("Martin", "Sara", 78L))
                .isEqualTo("MARSAR78");
    }

    // ── Uppercase guarantee ────────────────────────────────────────────────────

    @Test
    void generate_alwaysReturnsUppercase() {
        String result = service.generate("dupont", "pierre", 1L);
        assertThat(result).isEqualTo(result.toUpperCase());
        assertThat(result).isEqualTo("DUPPIE1");
    }

    // ── Accent removal ────────────────────────────────────────────────────────

    @Test
    void generate_accentedLastName_removesAccents() {
        // Élodie → ELODIEXX → first 3 = ELO
        // Ménard → MENARD → first 3 = MEN
        assertThat(service.generate("Élodie", "Ménard", 5L))
                .isEqualTo("ELOMEN5");
    }

    @Test
    void generate_accentedFirstName_removesAccents() {
        // Belaïd → BELAID → BEL
        // Hédi   → HEDI   → HED
        assertThat(service.generate("Belaïd", "Hédi", 12L))
                .isEqualTo("BELHED12");
    }

    @Test
    void generate_allAccentTypes_normalized() {
        // Ñoño → NFD → ñ decomposes to n+tilde → strip tilde → NON
        // Açık → NFD → Ç decomposes to C+cedilla → strip cedilla → C
        //              ı (U+0131 dotless-i) has no NFD decomposition → removed by [^a-zA-Z]
        //              k stays → ACK
        assertThat(service.generate("Ñoño", "Açık", 3L))
                .isEqualTo("NONACK3");
    }

    // ── Special character removal ──────────────────────────────────────────────

    @Test
    void generate_hyphenatedLastName_removesHyphen() {
        // El-Ayeb → ELAYEB → ELA
        // Sami    → SAMI   → SAM
        assertThat(service.generate("El-Ayeb", "Sami", 9L))
                .isEqualTo("ELASAM9");
    }

    @Test
    void generate_nameWithSpaces_removesSpaces() {
        // El Ayeb → ELAYEB → ELA
        assertThat(service.generate("El Ayeb", "Sami", 9L))
                .isEqualTo("ELASAM9");
    }

    @Test
    void generate_apostropheInName_removesApostrophe() {
        // N'Diaye → NDIAYE → NDI
        assertThat(service.generate("N'Diaye", "Omar", 7L))
                .isEqualTo("NDIOMA7");
    }

    // ── Short name padding ────────────────────────────────────────────────────

    @Test
    void generate_shortLastName_padsWithX() {
        // Bo → BO → BOX (padded to 3)
        // Li → LI → LIX (padded to 3)
        assertThat(service.generate("Bo", "Li", 1L))
                .isEqualTo("BOXLIX1");
    }

    @Test
    void generate_singleLetterName_padsWithXX() {
        // A → AXX
        // B → BXX
        assertThat(service.generate("A", "B", 2L))
                .isEqualTo("AXXBXX2");
    }

    @Test
    void generate_exactlyThreeLetters_nopadding() {
        assertThat(service.generate("Doe", "Ana", 10L))
                .isEqualTo("DOEANA10");
    }

    // ── Long names ────────────────────────────────────────────────────────────

    @Test
    void generate_longNames_takesOnlyFirstThree() {
        assertThat(service.generate("Vandermeersch", "Alexandre", 500L))
                .isEqualTo("VANALE500");
    }

    // ── Null / blank safety ───────────────────────────────────────────────────

    @Test
    void generate_nullLastName_usesXXX() {
        assertThat(service.generate(null, "Sara", 1L))
                .isEqualTo("XXXSAR1");
    }

    @Test
    void generate_blankFirstName_usesXXX() {
        assertThat(service.generate("Martin", "   ", 1L))
                .isEqualTo("MARXXX1");
    }

    // ── Uniqueness via userId ─────────────────────────────────────────────────

    @Test
    void generate_sameNameDifferentId_areDifferent() {
        String m1 = service.generate("Martin", "Sara", 1L);
        String m2 = service.generate("Martin", "Sara", 2L);
        assertThat(m1).isNotEqualTo(m2);
        assertThat(m1).endsWith("1");
        assertThat(m2).endsWith("2");
    }

    @Test
    void generate_differentNamesButSameId_areDifferent() {
        // Rare collision edge-case — if names differ, even same ID → different
        String m1 = service.generate("Dupont", "Pierre", 1L);
        String m2 = service.generate("Durand", "Paul",   1L);
        assertThat(m1).isNotEqualTo(m2);
    }
}
