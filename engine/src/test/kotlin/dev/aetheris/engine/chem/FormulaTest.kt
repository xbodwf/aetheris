package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormulaTest {

    @Test
    fun `parses simple formula and derives molar mass`() {
        val water = Formula.parse("H2O")
        assertEquals(2, water.countOf("H"))
        assertEquals(1, water.countOf("O"))
        assertEquals(0, water.charge)
        assertEquals(18.015, water.molarMass, 5e-3)
    }

    @Test
    fun `parses nested groups`() {
        val lime = Formula.parse("Ca(OH)2")
        assertEquals(1, lime.countOf("Ca"))
        assertEquals(2, lime.countOf("O"))
        assertEquals(2, lime.countOf("H"))
        assertEquals(74.092, lime.molarMass, 5e-3)

        val sulfate = Formula.parse("Fe2(SO4)3")
        assertEquals(2, sulfate.countOf("Fe"))
        assertEquals(3, sulfate.countOf("S"))
        assertEquals(12, sulfate.countOf("O"))
        assertEquals(399.858, sulfate.molarMass, 5e-3)
    }

    @Test
    fun `parses square-bracket complex groups`() {
        val ferrocyanide = Formula.parse("[Fe(CN)6]")
        assertEquals(1, ferrocyanide.countOf("Fe"))
        assertEquals(6, ferrocyanide.countOf("C"))
        assertEquals(6, ferrocyanide.countOf("N"))
        assertEquals(211.953, ferrocyanide.molarMass, 5e-3)
    }

    @Test
    fun `parses sugar and hydrate`() {
        val glucose = Formula.parse("C6H12O6")
        assertEquals(6, glucose.countOf("C"))
        assertEquals(12, glucose.countOf("H"))
        assertEquals(6, glucose.countOf("O"))
        assertEquals(180.156, glucose.molarMass, 5e-3)

        val blueVitriol = Formula.parse("CuSO4·5H2O")
        assertEquals(1, blueVitriol.countOf("Cu"))
        assertEquals(1, blueVitriol.countOf("S"))
        assertEquals(9, blueVitriol.countOf("O"))
        assertEquals(10, blueVitriol.countOf("H"))
        assertEquals(249.677, blueVitriol.molarMass, 5e-3)
    }

    @Test
    fun `accepts dot and star as hydrate separators`() {
        val a = Formula.parse("CuSO4.5H2O")
        val b = Formula.parse("CuSO4*5H2O")
        val c = Formula.parse("CuSO4·5H2O")
        assertEquals(c, a)
        assertEquals(c, b)
    }

    @Test
    fun `parses ionic charge`() {
        val sulfate = Formula.parse("SO4^2-")
        assertEquals(-2, sulfate.charge)
        assertEquals(4, sulfate.countOf("O"))

        val sodium = Formula.parse("Na^+")
        assertEquals(1, sodium.charge)

        val iron = Formula.parse("Fe^3+")
        assertEquals(3, iron.charge)
    }

    @Test
    fun `charge is part of equality`() {
        assertNotEquals(Formula.parse("Fe^2+"), Formula.parse("Fe^3+"))
        assertEquals(Formula.parse("Fe^3+"), Formula.of(mapOf("Fe" to 1), 3))
    }

    @Test
    fun `only reports elements outside the allowed set`() {
        val methane = Formula.parse("CH4")
        assertTrue(methane.only("C", "H"))
        assertTrue(methane.only("C", "H", "O"))
        assertFalse(methane.only("C"))
    }

    @Test
    fun `equals ignores insertion order`() {
        assertEquals(Formula.parse("H2O"), Formula.of("O" to 1, "H" to 2))
    }

    @Test
    fun `rejects malformed input`() {
        assertThrows(FormulaException::class.java) { Formula.parse("h2o") }
        assertThrows(FormulaException::class.java) { Formula.parse("Xx2") }
        assertThrows(FormulaException::class.java) { Formula.parse("Ca(OH") }
        assertThrows(FormulaException::class.java) { Formula.parse("") }
        assertThrows(FormulaException::class.java) { Formula.parse("H0") }
        assertThrows(FormulaException::class.java) { Formula.parse("Na^") }
        assertThrows(FormulaException::class.java) { Formula.parse("H2O)") }
    }

    @Test
    fun `pretty form uses subscripts and superscripts`() {
        assertEquals("H₂O", Formula.parse("H2O").pretty())
        assertEquals("C₆H₁₂O₆", Formula.parse("C6H12O6").pretty())
        assertEquals("Na⁺", Formula.parse("Na^+").pretty())
        assertEquals("OH⁻", Formula.parse("OH^-").pretty())
        assertEquals("Cu²⁺", Formula.parse("Cu^2+").pretty())
        assertEquals("Fe³⁺", Formula.parse("Fe^3+").pretty())
        assertEquals("SO₄²⁻", Formula.parse("SO4^2-").pretty())

        // Grouping is not retained, so the pretty form flattens it too - right composition, wrong shorthand.
        assertEquals("CaO₂H₂", Formula.parse("Ca(OH)2").pretty())

        // The machine-readable form is untouched, so it still round-trips through the parser.
        assertEquals("Cu^2+", Formula.parse("Cu^2+").toString())
        assertEquals("SO4^2-", Formula.parse("SO4^2-").toString())
    }

    @Test
    fun `elements table is complete and ordered`() {
        assertEquals(118, Elements.size)
        assertEquals(6, Elements.require("C").atomicNumber)
        assertEquals("Oxygen", Elements.require("O").name)
        assertEquals(null, Elements.of("Xx"))
        assertThrows(NoSuchElementException::class.java) { Elements.require("Xx") }
    }
}
