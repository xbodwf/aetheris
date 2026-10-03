package dev.aetheris.engine.data

import dev.aetheris.engine.chem.Formula
import dev.aetheris.engine.chem.FormulaException
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.SpeciesRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeciesCodecTest {

    private fun fixture(): String =
        javaClass.getResourceAsStream("/species/fixture.json")!!
            .bufferedReader()
            .readText()

    @Test
    fun `parses every record with its fields`() {
        val records = SpeciesCodec.parse(fixture())
        assertEquals(3, records.size)

        val methane = records.first { it.id == "test:methane" }
        assertEquals("Methane", methane.name)
        assertEquals("CH4", methane.formula)
        assertEquals(Phase.GAS, methane.phase)
        assertEquals(-74810.0, methane.formationEnthalpy, 1e-9)
        assertEquals(35.69, methane.heatCapacity, 1e-9)
        assertEquals(186.25, methane.entropy, 1e-9)

        assertNotNull(methane.nasa7)
        assertEquals(listOf(200.0, 1000.0, 6000.0), methane.nasa7!!.temperatureRanges)
        assertEquals(7, methane.nasa7!!.low.size)
        assertEquals(7, methane.nasa7!!.high.size)

        assertNull(records.first { it.id == "test:oxygen" }.nasa7)
    }

    @Test
    fun `load registers species and parses formula and charge`() {
        val registry = SpeciesRegistry()
        val loaded = SpeciesCodec.load(fixture(), registry)

        assertEquals(3, loaded.size)
        assertEquals(16.043, registry.require("test:methane").molarMass, 5e-3)

        val sulfate = registry.require("test:sulfate")
        assertEquals(Phase.AQUEOUS, sulfate.phase)
        assertEquals(-2, sulfate.formula.charge)
        assertEquals(4, sulfate.formula.countOf("O"))
    }

    @Test
    fun `encode round-trips through parse`() {
        val records = SpeciesCodec.parse(fixture())
        val reparsed = SpeciesCodec.parse(SpeciesCodec.encode(records))
        assertEquals(records, reparsed)
    }

    @Test
    fun `rejects a missing or wrong format marker`() {
        assertThrows(SpeciesFormatException::class.java) { SpeciesCodec.parse("{}") }
        assertThrows(SpeciesFormatException::class.java) {
            SpeciesCodec.parse("""{"format":"aetheris:species/v99","species":[]}""")
        }
    }

    @Test
    fun `rejects an unknown phase and a malformed formula`() {
        val badPhase = """
            {"format":"aetheris:species/v1","species":[
              {"id":"test:x","name":"X","formula":"H2O","phase":"PLASMA","hf":0.0,"cp":30.0} ]}
        """.trimIndent()
        assertThrows(SpeciesFormatException::class.java) { SpeciesCodec.parse(badPhase) }

        val badFormula = """
            {"format":"aetheris:species/v1","species":[
              {"id":"test:x","name":"X","formula":"Xx2","phase":"GAS","hf":0.0,"cp":30.0} ]}
        """.trimIndent()
        // The format is fine; the formula is rejected later, when it is loaded.
        SpeciesCodec.parse(badFormula)
        assertThrows(FormulaException::class.java) {
            SpeciesCodec.load(badFormula, SpeciesRegistry())
        }
    }

    @Test
    fun `rejects a nasa7 block with the wrong number of coefficients`() {
        val bad = """
            {"format":"aetheris:species/v1","species":[
              {"id":"test:x","name":"X","formula":"H2O","phase":"GAS","hf":0.0,"cp":30.0,
               "nasa7":{"tRanges":[200.0,1000.0,6000.0],"low":[1.0,2.0],"high":[1.0,2.0]}} ]}
        """.trimIndent()
        assertThrows(SpeciesFormatException::class.java) { SpeciesCodec.parse(bad) }
    }

    @Test
    fun `a registry can be built purely from data with no hardcoded substances`() {
        val registry = SpeciesRegistry()
        SpeciesCodec.load(fixture(), registry)
        assertTrue(registry.ids.containsAll(setOf("test:methane", "test:oxygen", "test:sulfate")))
        assertEquals(
            Formula.parse("SO4^2-"),
            registry.require("test:sulfate").formula,
        )
    }
}
