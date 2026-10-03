package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Taking a measured sample is the maths a test tube or a pipette needs, and getting it wrong is quiet: a
 * sample that is not the same concentration as its source still looks like a sample. These tests pin the
 * two properties that make it one - the concentration survives, and nothing is created or destroyed.
 */
class MixtureSampleTest {

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
        return registry
    }

    private fun vessel(registry: SpeciesRegistry): Mixture = Mixture(
        registry, 1.0, 300.0,
        mapOf(SpeciesRegistry.H2O_LIQUID to 51_000.0, WetChemistry.COPPER_2_ION to 500.0),
    )

    @Test
    fun `a sample is the same concentration as its source`() {
        val registry = registry()
        val source = vessel(registry)
        val before = source.molarity(WetChemistry.COPPER_2_ION)

        val sample = source.takeSample(1.0 / 64.0)!!

        assertEquals(before, sample.molarity(WetChemistry.COPPER_2_ION), before * 1e-6)
        // And the source is unchanged in concentration too: a proportional sample cannot dilute what it
        // came from, which is exactly why molarity is the right unit for a titration.
        assertEquals(before, source.molarity(WetChemistry.COPPER_2_ION), before * 1e-6)
    }

    @Test
    fun `a sample reads as a full cell so it can be carried and poured without a shrink factor`() {
        val source = vessel(registry())
        val sample = source.takeSample(1.0 / 64.0)!!
        assertEquals(1.0, sample.condensedVolume() / sample.volume, 1e-9)
        assertEquals(300.0, sample.temperature, 1e-9, "temperature comes along")
    }

    @Test
    fun `taking a sample conserves every species`() {
        val registry = registry()
        val source = vessel(registry)
        val waterBefore = source.amount(SpeciesRegistry.H2O_LIQUID)
        val copperBefore = source.amount(WetChemistry.COPPER_2_ION)

        val sample = source.takeSample(1.0 / 64.0)!!

        assertEquals(waterBefore, source.amount(SpeciesRegistry.H2O_LIQUID) + sample.amount(SpeciesRegistry.H2O_LIQUID), 1e-6)
        assertEquals(copperBefore, source.amount(WetChemistry.COPPER_2_ION) + sample.amount(WetChemistry.COPPER_2_ION), 1e-9)
        assertTrue(sample.amount(WetChemistry.COPPER_2_ION) > 0.0, "the sample carries the solute")
    }

    @Test
    fun `asking for more than is there takes everything`() {
        val registry = registry()
        val source = vessel(registry)
        val copper = source.amount(WetChemistry.COPPER_2_ION)

        val sample = source.takeSample(1_000.0)!!

        assertEquals(copper, sample.amount(WetChemistry.COPPER_2_ION), 1e-9)
        assertEquals(0.0, source.amount(WetChemistry.COPPER_2_ION), 1e-12)
    }

    @Test
    fun `an empty cell yields no sample`() {
        val registry = registry()
        assertNull(Mixture(registry, 1.0).takeSample(1.0 / 64.0))
    }

    @Test
    fun `absorbing a sample adds its matter without resizing the cell`() {
        val registry = registry()
        val target = Mixture(
            registry, 1.0, 298.15, mapOf(SpeciesRegistry.H2O_LIQUID to 51_000.0),
        )
        val sample = vessel(registry).takeSample(1.0 / 64.0)!!
        val copper = sample.amount(WetChemistry.COPPER_2_ION)
        val waterBefore = target.amount(SpeciesRegistry.H2O_LIQUID)

        target.absorb(sample)

        assertEquals(copper, target.amount(WetChemistry.COPPER_2_ION), 1e-9)
        assertTrue(target.amount(SpeciesRegistry.H2O_LIQUID) > waterBefore, "the sample's water came along")
        assertEquals(1.0, target.volume, 1e-12, "the target keeps its own size, so it dilutes")
    }
}
