package dev.aetheris.engine.data

import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Characterises the *shape* of a shipped reaction's progress, which is where a rate law actually
 * becomes visible to a player.
 *
 * Precipitation is third order in the ions, so it has no meaningful "time to completion": it
 * approaches saturation asymptotically and spends most of the wall clock on the last few percent.
 * Pinning the curve - a quick start, then a long tail - documents that, so that nobody re-tunes the
 * rate expecting a clean finish and concludes the engine is broken when 98% never arrives. It is also
 * why the companion test asserts a half-life rather than a completion time.
 */
class KineticsProbeTest {

    private fun locate(relative: String): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `dissolution is the slow direction and it stops at the solubility product`() {
        val gas = locate("common/src/main/resources/data/aetheris/species/nasa_gas.json")
        val aqueous = locate("common/src/main/resources/data/aetheris/species/aqueous.json")
        assumeTrue(gas != null && aqueous != null, "species data not present")

        val registry = SpeciesRegistry()
        SpeciesCodec.load(gas!!.readText(), registry)
        SpeciesCodec.load(aqueous!!.readText(), registry)
        val reactions = CombustionTemplate().instantiate(registry) +
            WetChemistry.precipitation.instantiate(registry)
        val engine = ReactionEngine(registry, reactions)

        // A mole of chalk in a litre of water. This probe used to measure *precipitation* from a solution
        // thousands of times past saturation and complain that it took too long; with rate constants set from
        // the physical timescale that direction is over in a step, and the interesting curve is this one.
        //
        // Why this direction is slower is not a tuning choice. A reversible reaction has one rate constant for
        // both directions - kReverse is kForward/K, which is detailed balance - so an approach from the
        // saturated side is K times slower than one from the supersaturated side. For calcite K is about 2e8,
        // and that factor is exactly why a lump of chalk sits there.
        val vessel = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(WetChemistry.WATER to 55.5, WetChemistry.CALCIUM_CARBONATE to 1.0),
        )

        val marks = listOf(1.0, 600.0, 3_600.0)
        val dissolvedAt = LinkedHashMap<Double, Double>()
        var seconds = 0.0
        for (mark in marks) {
            while (seconds < mark) {
                engine.step(vessel, 1.0)
                seconds += 1.0
            }
            dissolvedAt[mark] = vessel.amount(WetChemistry.CALCIUM_2_ION)
        }

        assertTrue(
            dissolvedAt.getValue(1.0) < dissolvedAt.getValue(600.0),
            "dissolution has to be progressing at all: ${dissolvedAt.getValue(1.0)} then ${dissolvedAt.getValue(600.0)}",
        )
        assertTrue(
            dissolvedAt.getValue(600.0) <= dissolvedAt.getValue(3_600.0),
            "and it must keep going rather than reverse",
        )
        // The ceiling is about 7e-5 mol/L for calcite; an hour gets a long way toward it without arriving, and
        // arriving exactly is not something an asymptotic approach ever does. SolubilityTest pins the ceiling
        // itself against the handbook; this pins only that the road to it is slow and monotone.
        val afterHour = dissolvedAt.getValue(3_600.0)
        assertTrue(afterHour < 1e-4) { "an hour must not overshoot saturation, but reached $afterHour" }
        assertTrue(vessel.amount(WetChemistry.CALCIUM_CARBONATE) > 0.99) { "the lump should still be a lump" }
    }
}
