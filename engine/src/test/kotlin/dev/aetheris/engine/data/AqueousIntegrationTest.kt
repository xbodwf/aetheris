package dev.aetheris.engine.data

import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.PhaseKind
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end on the *shipped* data: load the real NASA catalogue plus the curated aqueous slice,
 * build the engine the way the mod does, and run the target scenario.
 *
 *   CuSO4(aq) [setblock] + 2 NaOH(aq) [reagent] -> Cu(OH)2(s)↓ + Na2SO4(aq)
 *
 * which in the ion model is Cu^2+ + 2 OH^- -> Cu(OH)2(s) with Na^+ and SO4^2- spectators.
 *
 * The amounts are a full block of solution (1 m^3 of water, 0.5 M copper) rather than a token
 * quantity, so this exercises the same regime a player does. It deliberately does not demand that
 * *all* the copper precipitate: the rate law is third order in the ions, so the last few percent is
 * an asymptotic tail that takes hours, and asserting otherwise would only re-encode the placeholder
 * rate that used to finish the reaction inside a single step.
 */
class AqueousIntegrationTest {

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
    fun `copper sulfate solution precipitates copper hydroxide`() {
        val gas = locate("common/src/main/resources/data/aetheris/species/nasa_gas.json")
        val aqueous = locate("common/src/main/resources/data/aetheris/species/aqueous.json")
        assumeTrue(gas != null && aqueous != null, "species data not present")

        val registry = SpeciesRegistry()
        SpeciesCodec.load(gas!!.readText(), registry)
        SpeciesCodec.load(aqueous!!.readText(), registry)

        val reactions = CombustionTemplate().instantiate(registry) +
            WetChemistry.precipitation.instantiate(registry)
        val engine = ReactionEngine(registry, reactions)

        // Place a copper sulfate solution, then add sodium hydroxide next to it.
        val vessel = Mixture(
            registry, 1.0, 298.15,
            mapOf(
                WetChemistry.WATER to 51_000.0,
                WetChemistry.COPPER_2_ION to 500.0,
                WetChemistry.SULFATE_ION to 500.0,
            ),
        )
        val blue = vessel.solutionColor()
        assertNotEquals(null, blue, "copper sulfate solution should be coloured")

        vessel.add(WetChemistry.SODIUM_ION, 1_000.0)
        vessel.add(WetChemistry.HYDROXIDE_ION, 1_000.0)
        val elementsBefore = vessel.elementTotals()

        // An hour of game time: enough for the asymptotic tail to get most of the way.
        engine.simulate(vessel, 1.0, 3_600)

        val copperLeft = vessel.amount(WetChemistry.COPPER_2_ION)
        assertTrue(copperLeft < 50.0, "most of the copper should precipitate, $copperLeft mol left")
        val solid = vessel.phaseStates.firstOrNull { it.kind == PhaseKind.SOLID }
        assertTrue(solid != null, "a solid phase should have appeared")
        assertTrue(
            solid!!.amount(WetChemistry.COPPER_2_HYDROXIDE) > 450.0,
            "the precipitate should account for the missing copper",
        )
        // Sodium and sulfate stay dissolved (Na2SO4 in solution).
        assertEquals(1_000.0, vessel.amount(WetChemistry.SODIUM_ION), 1e-6)
        assertEquals(500.0, vessel.amount(WetChemistry.SULFATE_ION), 1e-6)

        // The colour does not merely fade, it goes: with the physical rate constants essentially all of the
        // copper is out of solution inside a step, `solutionColor` answers null when nothing coloured is
        // dissolved, and that null is the honest reading rather than a failure to find a colour.
        assertNull(vessel.solutionColor(), "the blue should be gone once the copper has precipitated")

        assertEquals(0.0, vessel.charge(), 1e-9)
        for ((element, moles) in elementsBefore) {
            // Relative, because an absolute tolerance on 500 mol is a stricter demand than one on 0.5, and
            // conservation is a statement about proportions. The clamped single-step path leaves a few parts in
            // 1e12, which is floating-point noise rather than chemistry.
            assertEquals(moles, vessel.elementTotals().getValue(element), moles * 1e-9, "element $element")
        }
    }
}
