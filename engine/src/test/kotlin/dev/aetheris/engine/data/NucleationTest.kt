package dev.aetheris.engine.data

import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.Nucleation
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Nucleation, which is the difference between "slow" and "does not happen".
 *
 * The numbers to keep in mind: calcite's interfacial energy puts its barrier at nearly two thousand thermal
 * energies when the solution is twice saturated, and at about two when it is a hundred million times saturated.
 * `exp(-1881)` is not a small rate, it is zero; `exp(-2.5)` is a rate that finishes inside one engine step. So
 * the same chemistry produces "sits there forever" and "appears at once", decided by how far past saturation the
 * solution is - and by whether a crystal is already there, because growth clears no barrier at all.
 */
class NucleationTest {

    private fun resource(name: String): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "common/src/main/resources/data/aetheris/species/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun engine(): ReactionEngine? {
        val files = listOf("nasa_gas.json", "aqueous.json").map { resource(it) }
        assumeTrue(files.all { it != null }, "species data not present")
        val registry = SpeciesRegistry()
        for (file in files) SpeciesCodec.load(file!!.readText(), registry)
        return ReactionEngine(registry, WetChemistry.precipitation.instantiate(registry))
    }

    @Test
    fun `the barrier is enormous when a solution is only slightly supersaturated`() {
        val volume = 3.69e-5

        // Seawater's situation, and the reason a coral reef is a reef: three times saturated, and nothing
        // happens. The number is the whole point - it is not a slow rate, it is no rate.
        val mild = Nucleation.criticalBarrier(3.0, volume, Constants.T0)
        assertTrue(mild > 400.0) { "three times saturated should be hopeless, but the barrier was $mild kT" }
        assertTrue(Nucleation.factor(3.0, volume, Constants.T0) == 0.0) { "exp(-400) is zero, not small" }
    }

    @Test
    fun `a hundred million times supersaturated has almost no barrier left`() {
        // Which is a mole of calcium and a mole of carbonate in a litre against a Ksp of 5e-9.
        val factor = Nucleation.factor(1.0e8, 3.69e-5, Constants.T0)
        assertTrue(factor > 0.05) { "this far past saturation the solid should come out at once, factor $factor" }
    }

    @Test
    fun `the barrier falls as the square of the logarithm of supersaturation`() {
        val volume = 3.69e-5
        val a = Nucleation.criticalBarrier(1.0e4, volume, Constants.T0)
        val b = Nucleation.criticalBarrier(1.0e8, volume, Constants.T0)

        // ln(1e8)/ln(1e4) is 2, so the barrier - which goes as 1/ln(S)^2 - must fall by a factor of four.
        assertTrue(b < a / 3.5 && b > a / 4.5) { "expected a fourfold fall, got $a then $b" }
    }

    @Test
    fun `a metastable solution stays metastable, and a seed crystal settles it`() {
        val engine = engine() ?: return
        val registry = engine.registry

        // Twice saturation in each ion, so four times the solubility product: 1.4e-4 mol/L against a saturation
        // of 7e-5, and (1.4e-4)^2 against a Ksp of 4.9e-9.
        fun vessel() = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(
                WetChemistry.WATER to 55.5,
                WetChemistry.CALCIUM_2_ION to 1.4e-4,
                WetChemistry.CARBONATE_ION to 1.4e-4,
            ),
        )

        val metastable = vessel()
        engine.simulate(metastable, 1.0, 600)
        assertTrue(
            metastable.amount(WetChemistry.CALCIUM_CARBONATE) == 0.0,
            "ten minutes in a twice-saturated solution should produce no solid at all",
        )

        // The same solution, with a trace of the solid in it. Growth has no barrier, so now it goes.
        val seeded = vessel()
        seeded.add(WetChemistry.CALCIUM_CARBONATE, 1.0e-9)
        engine.simulate(seeded, 1.0, 600)
        assertTrue(
            seeded.amount(WetChemistry.CALCIUM_CARBONATE) > 1.0e-6,
            "a seed should turn a metastable solution into a precipitating one, but only " +
                "${seeded.amount(WetChemistry.CALCIUM_CARBONATE)} mol of solid exists",
        )
    }

    @Test
    fun `shaking is the same thing as seeding, which is what the tube does`() {
        val engine = engine() ?: return
        val registry = engine.registry

        val shaken = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(
                WetChemistry.WATER to 55.5,
                WetChemistry.CALCIUM_2_ION to 1.4e-4,
                WetChemistry.CARBONATE_ION to 1.4e-4,
            ),
        )

        // The engine's answer to agitation: put a surface in it. Not a rate change, and not a scripted
        // precipitate - the solid that appears is whatever the chemistry would have made on its own.
        val formed = engine.seed(shaken)
        assertEquals(listOf(WetChemistry.CALCIUM_CARBONATE), formed)

        engine.simulate(shaken, 1.0, 600)
        assertTrue(shaken.amount(WetChemistry.CALCIUM_CARBONATE) > 1.0e-6) { "the seed should have grown" }
    }

    @Test
    fun `shaking a solution with nothing to give does nothing at all`() {
        val engine = engine() ?: return
        val registry = engine.registry

        // Well under saturation, so there is no phase to nucleate and the gesture is honestly empty.
        val dilute = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(
                WetChemistry.WATER to 55.5,
                WetChemistry.CALCIUM_2_ION to 1.0e-6,
                WetChemistry.CARBONATE_ION to 1.0e-6,
            ),
        )

        assertTrue(engine.seed(dilute).isEmpty(), "there is nothing here to crystallise")
        assertTrue(dilute.amount(WetChemistry.CALCIUM_CARBONATE) == 0.0, "and nothing should have appeared")
    }
}
