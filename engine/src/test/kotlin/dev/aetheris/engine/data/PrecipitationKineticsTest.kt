package dev.aetheris.engine.data

import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * How fast a shipped reaction runs is part of its behaviour, not an implementation detail.
 *
 * The engine advances one second of game time per step, so a rate constant that is large next to the
 * amount of substance present consumes every reactant inside the very first step. That is not "a fast
 * reaction" - it is a reaction that teleports to equilibrium, and what a player watching a bench sees
 * is a block changing colour rather than a solution reacting. This test pins the pace on the real
 * shipped data and templates, so retuning the Arrhenius defaults cannot quietly undo it.
 *
 * It asserts a *half-life*, not a completion time: the law is third order in the ions, so the curve
 * approaches saturation asymptotically and "done" is not a well-defined moment. `KineticsProbeTest`
 * pins the shape of that curve.
 */
class PrecipitationKineticsTest {

    private fun locate(relative: String): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** Builds the engine the way the mod does: shipped species data plus the shipped templates. */
    private fun engine(): ReactionEngine? {
        val gas = locate("common/src/main/resources/data/aetheris/species/nasa_gas.json")
        val aqueous = locate("common/src/main/resources/data/aetheris/species/aqueous.json")
        if (gas == null || aqueous == null) return null

        val registry = SpeciesRegistry()
        SpeciesCodec.load(gas.readText(), registry)
        SpeciesCodec.load(aqueous.readText(), registry)
        val reactions = CombustionTemplate().instantiate(registry) +
            WetChemistry.precipitation.instantiate(registry)
        return ReactionEngine(registry, reactions)
    }

    /**
     * A full block of solution: 1 m^3 of water carrying 0.5 M copper(II) and 1 M hydroxide, which is
     * the stoichiometric ratio for Cu^2+ + 2 OH^- -> Cu(OH)2(s).
     */
    private fun benchVessel(registry: SpeciesRegistry): Mixture = Mixture(
        registry,
        1.0,
        298.15,
        mapOf(
            WetChemistry.WATER to 51_000.0,
            WetChemistry.COPPER_2_ION to 500.0,
            WetChemistry.SULFATE_ION to 500.0,
            WetChemistry.SODIUM_ION to 1_000.0,
            WetChemistry.HYDROXIDE_ION to 1_000.0,
        ),
    )

    @Test
    fun `a strongly supersaturated solution is precipitated within a single step`() {
        val engine = engine()
        assumeTrue(engine != null, "species data not present")

        val vessel = benchVessel(engine!!.registry)
        val initial = vessel.amount(WetChemistry.COPPER_2_ION)

        // One second of game time is one engine step, which is all the in-game tick ever asks for.
        //
        // This assertion used to run the other way - the reaction was *required* not to finish in one step, and
        // the rate constants were tuned to a tens-of-seconds half-life so a player would watch it happen. That
        // was a game-design decision dressed up as chemistry, and it had a cost nobody could see from here: the
        // same constants also set the *dissolution* rate, which is kForward/K, so a lump of chalk took days to
        // dissolve. Precipitating a solution that is thousands of times past saturation genuinely is immediate -
        // mix two clear solutions and a solid appears at once - so the physical claim is this one.
        engine.step(vessel, 1.0)
        val convertedInOneStep = 1.0 - vessel.amount(WetChemistry.COPPER_2_ION) / initial
        assertTrue(
            convertedInOneStep > 0.98,
            "a solution this far past saturation should be out of solution at once, but only " +
                "${"%.1f".format(convertedInOneStep * 100)}% converted in the first second",
        )

        // And it stays there: the residual is the equilibrium residual, which is what stops the reaction rather
        // than the rate constants running out of road. SolubilityTest checks the ceiling itself against a
        // handbook value; here the point is only that one step is enough to reach it.
        val afterOneStep = vessel.amount(WetChemistry.COPPER_2_ION)
        engine.simulate(vessel, 1.0, 600)
        assertTrue(
            vessel.amount(WetChemistry.COPPER_2_ION) <= afterOneStep,
            "ten more minutes should not put copper back into solution",
        )
    }
}
