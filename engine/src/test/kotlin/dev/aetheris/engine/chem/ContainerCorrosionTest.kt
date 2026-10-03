package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Corrosion is the acid's own chemistry applied to the container, so these tests are really about whether the
 * numbers already say the right thing without a rule being written for it.
 *
 * The two that matter are the first pair: a litre of dilute acid must *stain* a bucket and stop, because it
 * carries one mole of protons and half a mole of iron is 28 g against a bucket's ~200 g, and a concentrated
 * acid must eat through. Neither outcome is scripted - both fall out of the stoichiometry.
 */
class ContainerCorrosionTest {

    private fun registry(): SpeciesRegistry {
        val registry = SpeciesRegistry()
        registry.register(Species("test:iron", "Iron", Formula.parse("Fe"), Phase.SOLID, 0.0, 25.1))
        registry.register(
            Species("test:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 1.0),
        )
        registry.register(
            Species(
                "test:iron_2_ion", "Iron(II) ion", Formula.parse("Fe^2+"), Phase.AQUEOUS,
                -89_100.0, 25.0, entropy = -105.6,
            ),
        )
        registry.register(
            Species(SpeciesRegistry.H2, "Hydrogen", Formula.parse("H2"), Phase.GAS, 0.0, 28.84),
        )
        registry.register(
            Species(
                SpeciesRegistry.H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID,
                -285_830.0, 75.3, solventGroup = "water",
            ),
        )
        return registry
    }

    private fun engine(registry: SpeciesRegistry): ReactionEngine =
        ReactionEngine(registry, MetalAcidTemplate(metals = mapOf("test:iron" to 2)).instantiate(registry))

    /** A litre of acid, which is one cell: see [FluidUnits]. */
    private fun bucketOf(registry: SpeciesRegistry, acidMoles: Double): Mixture = Mixture(
        registry, FluidUnits.CELL_VOLUME, Constants.T0,
        mapOf(SpeciesRegistry.H2O_LIQUID to 55.5, "test:hydrogen_ion" to acidMoles),
    )

    /** A bucket's worth of iron: about 200 g, which is what a real one weighs. */
    private val bucket = ContainerCorrosion.Material("test:iron", 3.6)

    private fun corrode(
        engine: ReactionEngine,
        contents: Mixture,
        seconds: Int,
    ): ContainerCorrosion.Outcome {
        var material = bucket
        var outcome = ContainerCorrosion.Outcome(material, ruptured = false)
        for (step in 0 until seconds) {
            outcome = ContainerCorrosion.step(engine, contents, material, 1.0)
            material = outcome.material
            if (outcome.ruptured) break
        }
        return outcome
    }

    @Test
    fun `a dilute acid stains a bucket and stops instead of eating through it`() {
        val registry = registry()
        val contents = bucketOf(registry, acidMoles = 1.0)

        val outcome = corrode(engine(registry), contents, seconds = 3_000)

        assertFalse(outcome.ruptured, "one mole of protons cannot consume 3.6 mol of iron")
        // Nearly spent rather than spent: the rate law is second order in H+, so its tail is long. See
        // MetalAcidTest for the half-life this pace is calibrated against.
        assertTrue(contents.amount("test:hydrogen_ion") < 0.1, "the acid should be nearly used up")
        // Half a mole of iron gone out of 3.6: a stained bucket, not a broken one.
        assertEquals(0.139, outcome.damage, 0.02)
    }

    @Test
    fun `a concentrated acid eats through the bucket`() {
        val registry = registry()
        val contents = bucketOf(registry, acidMoles = 10.0)

        val outcome = corrode(engine(registry), contents, seconds = 600)

        assertTrue(outcome.ruptured, "ten moles of protons can dissolve five moles of iron")
        assertEquals(1.0, outcome.damage, 1e-9)
    }

    @Test
    fun `what the container became stays in the contents, but the container itself does not`() {
        val registry = registry()
        val contents = bucketOf(registry, acidMoles = 1.0)

        val outcome = ContainerCorrosion.step(engine(registry), contents, bucket, dt = 60.0)

        // The iron it turned into is dissolved in the acid, which is the visible consequence: an iron bucket
        // of acid ends up holding iron(II) ions.
        assertTrue(contents.amount("test:iron_2_ion") > 0.1)
        // The container is not part of its own contents, or its moles would show up in a readout of the
        // liquid and every fill fraction would be wrong.
        assertEquals(0.0, contents.amount("test:iron"), 1e-12)
        assertTrue(outcome.remaining < bucket.moles)
    }

    @Test
    fun `a glass container is untouched because its material is not a reagent here`() {
        val registry = registry()
        val contents = bucketOf(registry, acidMoles = 10.0)

        // Glass is not attacked by hydrochloric acid, so there is no reaction to find: the same call simply
        // reports no damage, which is what makes a test tube a safe thing to carry acid in and a bucket not.
        val outcome = ContainerCorrosion.step(
            engine(registry), contents, ContainerCorrosion.Material("test:glass", 1.0), dt = 600.0,
        )
        assertEquals(1.0, outcome.remaining, 1e-12)
        assertEquals(0.0, outcome.damage, 1e-12)
        assertFalse(outcome.ruptured)
    }
}
