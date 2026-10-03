package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Venting is the difference between a solution and a pressure vessel, and getting it wrong is quiet: the
 * mixture stays perfectly conserved, it simply reports chemistry that cannot exist - half a mole of hydrogen
 * "dissolved" in a litre of water, in a bucket, at one atmosphere.
 */
class VentingTest {

    private fun registry(): SpeciesRegistry {
        val registry = SpeciesRegistry()
        registry.register(
            Species(
                SpeciesRegistry.H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID,
                -285_830.0, 75.3, solventGroup = "water",
            ),
        )
        registry.register(
            Species("test:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 1.0),
        )
        registry.register(
            Species(
                SpeciesRegistry.H2, "Hydrogen", Formula.parse("H2"), Phase.GAS, 0.0, 28.84,
            ),
        )
        return registry
    }

    /** What a spent bucket of acid holds: iron(II) chloride in water, plus the hydrogen it made. */
    private fun bucket(registry: SpeciesRegistry) = Mixture(
        registry, FluidUnits.CELL_VOLUME, Constants.T0,
        mapOf(SpeciesRegistry.H2O_LIQUID to 55.5, "test:hydrogen_ion" to 0.03, SpeciesRegistry.H2 to 0.487),
    )

    @Test
    fun `a gas bubbles out and the solution keeps everything else`() {
        val registry = registry()
        val mixture = bucket(registry)
        val water = mixture.amount(SpeciesRegistry.H2O_LIQUID)
        val acid = mixture.amount("test:hydrogen_ion")

        val vented = mixture.ventGases()

        assertEquals(0.487, vented.getValue(SpeciesRegistry.H2), 1e-12)
        assertEquals(0.0, mixture.amount(SpeciesRegistry.H2), 1e-12)
        assertEquals(water, mixture.amount(SpeciesRegistry.H2O_LIQUID), 1e-12)
        assertEquals(acid, mixture.amount("test:hydrogen_ion"), 1e-12)
    }

    @Test
    fun `venting loses nothing but the gas`() {
        val registry = registry()
        val mixture = bucket(registry)
        val before = mixture.snapshot().values.sum()
        val vented = mixture.ventGases().values.sum()
        assertEquals(before, mixture.snapshot().values.sum() + vented, 1e-9)
    }

    @Test
    fun `a mixture with no gas vents nothing and a pure solution is untouched`() {
        val registry = registry()
        val mixture = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(SpeciesRegistry.H2O_LIQUID to 55.5, "test:hydrogen_ion" to 1.0),
        )

        assertTrue(mixture.ventGases().isEmpty())
        assertEquals(1.0, mixture.amount("test:hydrogen_ion"), 1e-12)
    }

    @Test
    fun `venting the hydrogen lifts the pressure back to the atmosphere`() {
        val registry = registry()
        val mixture = bucket(registry)
        // Before: the headspace is carrying a real overpressure, which is the absurdity being removed.
        assertTrue(mixture.partialPressure(SpeciesRegistry.H2) > Constants.P0)

        mixture.ventGases()

        assertEquals(0.0, mixture.partialPressure(SpeciesRegistry.H2), 1e-12)
    }
}
