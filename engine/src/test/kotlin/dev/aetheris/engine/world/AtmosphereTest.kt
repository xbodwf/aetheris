package dev.aetheris.engine.world

import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.SpeciesRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ambient air thins with altitude, and float/sink follows from comparing densities. */
class AtmosphereTest {

    private val registry = SpeciesRegistry.defaults()

    @Test
    fun `sea-level air density is about 1_2 kg per cubic metre`() {
        val air = Atmosphere()
        assertEquals(1.225, air.densityAtAltitude(0.0), 0.01)
        // One scale height up, pressure and density fall by a factor of e.
        assertEquals(air.seaLevelPressure / Math.E, air.pressureAtAltitude(air.scaleHeight), 1.0)
    }

    @Test
    fun `air thins with altitude, visibly over a minecraft-sized build`() {
        val air = Atmosphere.MINECRAFT_LIKE
        val sea = air.densityAtBlock(62.0)
        val high = air.densityAtBlock(162.0)
        assertTrue(high < sea, "air at y=162 must be thinner than at y=62")
        // 100 blocks up at 8 m/block is 800 m, one scale height: density should roughly 1/e.
        assertTrue(high < sea * 0.5, "rarefaction should be clearly visible, got $high vs $sea")
    }

    @Test
    fun `helium rises and carbon dioxide sinks in air`() {
        val air = Atmosphere().densityAtAltitude(0.0)
        val helium = Mixture(registry, 1.0, 288.15, mapOf("aetheris:helium" to 40.9)).gasDensity()
        val co2 = Mixture(registry, 1.0, 288.15, mapOf(SpeciesRegistry.CO2 to 40.9)).gasDensity()

        assertTrue(Buoyancy.rises(helium, air), "helium ($helium) should be lighter than air ($air)")
        assertFalse(Buoyancy.rises(co2, air), "CO2 ($co2) should be heavier than air ($air)")
        assertTrue(Buoyancy.netUpwardForce(1.0, helium, air) > 0.0)
        assertTrue(Buoyancy.netUpwardForce(1.0, co2, air) < 0.0)
    }

    @Test
    fun `a light object can stop rising as the air thins`() {
        // Something slightly lighter than sea-level air rises low down but sinks higher up.
        val air = Atmosphere.MINECRAFT_LIKE
        val density = 0.9 // heavier than air high up, lighter than air at sea level
        assertTrue(Buoyancy.rises(density, air.densityAtBlock(62.0)))
        assertFalse(Buoyancy.rises(density, air.densityAtBlock(250.0)))
    }
}
