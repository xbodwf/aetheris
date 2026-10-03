package dev.aetheris.engine.data

import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
            mapOf(WetChemistry.WATER to 1000.0, WetChemistry.COPPER_2_ION to 1.0, WetChemistry.SULFATE_ION to 1.0),
        )
        val blue = vessel.solutionColor()
        assertNotEquals(null, blue, "copper sulfate solution should be coloured")

        vessel.add(WetChemistry.SODIUM_ION, 2.0)
        vessel.add(WetChemistry.HYDROXIDE_ION, 2.0)
        val elementsBefore = vessel.elementTotals()

        engine.simulate(vessel, 1.0, 3000)

        assertTrue(vessel.amount(WetChemistry.COPPER_2_ION) < 0.05, "copper should precipitate")
        val solid = vessel.phaseStates.firstOrNull { it.kind == dev.aetheris.engine.chem.PhaseKind.SOLID }
        assertTrue(solid != null && solid.amount(WetChemistry.COPPER_2_HYDROXIDE) > 0.95)
        // Sodium and sulfate stay dissolved (Na2SO4 in solution).
        assertEquals(2.0, vessel.amount(WetChemistry.SODIUM_ION), 1e-6)
        assertEquals(1.0, vessel.amount(WetChemistry.SULFATE_ION), 1e-6)

        // The colour fades as the blue copper ion leaves the solution (toward colourless).
        val after = vessel.solutionColor()
        assertNotEquals(blue, after)
        assertTrue(
            ((after!! shr 16) and 0xFF) > ((blue!! shr 16) and 0xFF),
            "the tint should fade toward white as copper precipitates",
        )

        assertEquals(0.0, vessel.charge(), 1e-9)
        for ((element, moles) in elementsBefore) {
            assertEquals(moles, vessel.elementTotals().getValue(element), 1e-9, "element $element")
        }
    }
}
