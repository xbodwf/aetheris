package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeciesRegistryTest {

    @Test
    fun `default catalogue resolves the well-known ids`() {
        val r = SpeciesRegistry.defaults()
        for (id in listOf(
            SpeciesRegistry.O2,
            SpeciesRegistry.N2,
            SpeciesRegistry.H2,
            SpeciesRegistry.CO,
            SpeciesRegistry.CO2,
            SpeciesRegistry.H2O_GAS,
            SpeciesRegistry.H2O_LIQUID,
            SpeciesRegistry.CH4,
        )) {
            assertTrue(r.contains(id), "missing species '$id'")
            assertNotNull(r.get(id))
        }
    }

    @Test
    fun `lookup of an unknown id is null but require throws`() {
        val r = SpeciesRegistry.defaults()
        assertNull(r.get("aetheris:unobtainium"))
        assertFalse(r.contains("aetheris:unobtainium"))
        assertThrows(NoSuchElementException::class.java) { r.require("aetheris:unobtainium") }
    }

    @Test
    fun `registering a duplicate id is rejected`() {
        val r = SpeciesRegistry()
        r.register(Species("aetheris:test", "Test", Formula.parse("H2O"), Phase.GAS, 0.0, 30.0))
        assertThrows(IllegalArgumentException::class.java) {
            r.register(Species("aetheris:test", "Test again", Formula.parse("H2O"), Phase.GAS, 0.0, 30.0))
        }
    }

    @Test
    fun `species ids must be namespaced`() {
        assertThrows(IllegalArgumentException::class.java) {
            Species("water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.30)
        }
    }

    @Test
    fun `species derive molar mass from the element table`() {
        val methane = SpeciesRegistry.defaults().require(SpeciesRegistry.CH4)
        assertEquals(16.043, methane.molarMass, 5e-3)
        assertEquals(Phase.GAS, methane.phase)
    }

    @Test
    fun `sensible internal energy equals formation enthalpy at the reference temperature`() {
        val co2 = SpeciesRegistry.defaults().require(SpeciesRegistry.CO2)
        assertEquals(co2.formationEnthalpy, co2.internalEnergy(Constants.T0), 1e-6)
        // Warmer than reference adds cp * dT.
        assertEquals(
            co2.formationEnthalpy + co2.heatCapacity * 100.0,
            co2.internalEnergy(Constants.T0 + 100.0),
            1e-6,
        )
    }

    @Test
    fun `byFormula distinguishes allotropes and phases`() {
        val r = SpeciesRegistry.defaults()

        val carbon = r.byFormula(Formula.parse("C"))
        assertEquals(2, carbon.size)
        assertTrue(carbon.all { it.phase == Phase.SOLID })

        val waterAll = r.byFormula(Formula.parse("H2O"))
        assertEquals(2, waterAll.size)
        assertEquals(1, r.byFormula(Formula.parse("H2O"), Phase.GAS).size)
        assertEquals(1, r.byFormula(Formula.parse("H2O"), Phase.LIQUID).size)
        assertSame(r.require(SpeciesRegistry.H2O_LIQUID), r.byFormula(Formula.parse("H2O"), Phase.LIQUID).single())
    }

    @Test
    fun `ids and all expose every registered species`() {
        val r = SpeciesRegistry.defaults()
        assertEquals(r.all.size, r.ids.size)
        assertTrue(r.all.isNotEmpty())
    }
}
