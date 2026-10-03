package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The goggle readout shows a player what a solution contains, so the selection behind it - what counts
 * as a solute, what counts as the medium, and what is worth listing first - is worth pinning down. It is
 * deliberately engine-side and Minecraft-free: the client only formats and translates the result.
 */
class SolutionReadoutTest {

    /** The aqueous slice the readout is built for, registered by hand so the test owns its own data. */
    private fun registry(): SpeciesRegistry {
        val registry = SpeciesRegistry()
        registry.register(
            Species(
                SpeciesRegistry.H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID,
                -285_830.0, 75.3, solventGroup = "water",
            ),
        )
        registry.register(
            Species(
                WetChemistry.COPPER_2_ION, "Copper(II) ion", Formula.parse("Cu^2+"), Phase.AQUEOUS,
                64_900.0, 25.0, color = 0xFF1E90FF.toInt(),
            ),
        )
        registry.register(
            Species(
                WetChemistry.SULFATE_ION, "Sulfate ion", Formula.parse("SO4^2-"), Phase.AQUEOUS,
                -909_270.0, 25.0,
            ),
        )
        registry.register(
            Species(
                WetChemistry.COPPER_2_HYDROXIDE, "Copper(II) hydroxide", Formula.parse("Cu(OH)2"),
                Phase.SOLID, -449_800.0, 98.0,
            ),
        )
        return registry
    }

    @Test
    fun `the solvent is left out and the solutes are reported with their molarity`() {
        val registry = registry()
        val vessel = Mixture(
            registry, 1.0, 298.15,
            mapOf(
                SpeciesRegistry.H2O_LIQUID to 51_000.0,
                WetChemistry.COPPER_2_ION to 500.0,
                WetChemistry.SULFATE_ION to 500.0,
            ),
        )

        val readout = SolutionNames.readout(vessel)
        assertEquals(2, readout.size, "water is the medium, not a solute")
        assertTrue(readout.none { it.id == SpeciesRegistry.H2O_LIQUID })

        val copper = readout.first { it.id == WetChemistry.COPPER_2_ION }
        assertEquals("Cu^2+", copper.formula)
        assertEquals(Phase.AQUEOUS, copper.phase)
        assertTrue(copper.molarity > 0.4, "about 0.5 mol/L, was ${copper.molarity}")
    }

    @Test
    fun `a precipitate is reported in moles, because a solid has no concentration`() {
        val registry = registry()
        val vessel = Mixture(
            registry, 1.0, 298.15,
            mapOf(
                SpeciesRegistry.H2O_LIQUID to 51_000.0,
                WetChemistry.COPPER_2_ION to 100.0,
                WetChemistry.COPPER_2_HYDROXIDE to 400.0,
            ),
        )

        val readout = SolutionNames.readout(vessel)
        // Largest first: the precipitate outweighs what is left dissolved.
        assertEquals(WetChemistry.COPPER_2_HYDROXIDE, readout.first().id)
        assertEquals(Phase.SOLID, readout.first().phase)
        assertEquals(400.0, readout.first().moles, 1e-9)
        assertEquals(0.0, readout.first().molarity, 1e-12, "a solid has no molarity")
    }

    @Test
    fun `a pure medium reads as having nothing in it, whatever the medium is`() {
        val registry = registry()

        val water = Mixture(registry, 1.0, 298.15, mapOf(SpeciesRegistry.H2O_LIQUID to 55_000.0))
        assertTrue(SolutionNames.readout(water).isEmpty(), "pure water holds no solutes")

        // The rule is "drop the largest liquid", not "drop water", so another medium behaves the same.
        val ethanol = registry.register(
            Species(
                "test:ethanol", "Ethanol", Formula.parse("C2H6O"), Phase.LIQUID, -277_690.0, 112.3,
                solventGroup = "water",
            ),
        )
        val pure = Mixture(registry, 1.0, 298.15, mapOf(ethanol.id to 17_000.0))
        assertTrue(SolutionNames.readout(pure).isEmpty(), "pure ethanol holds no solutes either")
    }

    @Test
    fun `the colour of each species comes through so a readout can tint its own lines`() {
        val registry = registry()
        val vessel = Mixture(
            registry, 1.0, 298.15,
            mapOf(SpeciesRegistry.H2O_LIQUID to 51_000.0, WetChemistry.COPPER_2_ION to 10.0),
        )

        assertEquals(0xFF1E90FF.toInt(), SolutionNames.readout(vessel).single().color)
    }
}
