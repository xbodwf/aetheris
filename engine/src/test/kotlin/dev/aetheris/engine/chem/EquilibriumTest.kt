package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Chemical equilibrium derived from thermodynamics: `dG = dH - T dS`, `K = exp(-dG / R T)`,
 * and a reversible reaction that stops when `Q = K`. No equilibrium constant is ever authored —
 * entropy (previously unused) plus formation enthalpy produce it.
 */
class EquilibriumTest {

    private fun solvent(): SpeciesRegistry {
        val r = SpeciesRegistry()
        r.register(
            Species("eq:water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3, solventGroup = "water")
        )
        return r
    }

    @Test
    fun `equilibrium constant follows dG equals dH minus T dS`() {
        val r = SpeciesRegistry()
        r.register(Species("eq:x", "X", Formula.parse("H2O"), Phase.AQUEOUS, -100_000.0, 50.0, entropy = 100.0))
        r.register(Species("eq:y", "Y", Formula.parse("H3O^+"), Phase.AQUEOUS, -110_000.0, 50.0, entropy = 150.0))
        val reaction = Reaction(
            "eq:x_to_y",
            reactants = mapOf("eq:x" to 1.0),
            products = mapOf("eq:y" to 1.0),
            activationEnergy = 10_000.0,
            preExponential = 1.0e3,
        )
        // dH = -10000, dS = +50, so dG(298.15) = -24907.5 J/mol and K = exp(24907.5 / R T).
        val expected = Math.exp(24_907.5 / (8.314462618 * 298.15))
        assertEquals(expected, reaction.equilibriumConstant(r, 298.15), expected * 1e-6)
    }

    /** A ⇌ B with dH = 0 and dS = R ln 3, so K = 3 at every temperature. */
    private fun equilibriumRegistry(): Pair<SpeciesRegistry, Reaction> {
        val r = solvent()
        r.register(Species("eq:a", "A", Formula.parse("C2H4O2"), Phase.AQUEOUS, 0.0, 50.0, entropy = 0.0))
        r.register(
            Species("eq:b", "B", Formula.parse("C2H4O2"), Phase.AQUEOUS, 0.0, 50.0, entropy = 8.314462618 * Math.log(3.0))
        )
        val reaction = Reaction(
            "eq:a_to_b",
            reactants = mapOf("eq:a" to 1.0),
            products = mapOf("eq:b" to 1.0),
            activationEnergy = 10_000.0,
            preExponential = 1.0e4,
            reversible = true,
        )
        return r to reaction
    }

    @Test
    fun `reversible reaction settles where Q equals K`() {
        val (r, reaction) = equilibriumRegistry()
        val engine = ReactionEngine(r, listOf(reaction))
        val mixture = Mixture(r, 1.0, 298.15, mapOf("eq:water" to 1000.0, "eq:a" to 1.0))

        engine.simulate(mixture, 1.0, 400)

        // K = 3 => at equilibrium b/a = 3 with a + b = 1, i.e. a = 0.25, b = 0.75.
        assertEquals(0.25, mixture.amount("eq:a"), 0.01)
        assertEquals(0.75, mixture.amount("eq:b"), 0.01)
        assertEquals(3.0, mixture.reactionQuotient(reaction), 0.1)
    }

    @Test
    fun `reversible reaction runs backwards from the products`() {
        val (r, reaction) = equilibriumRegistry()
        val engine = ReactionEngine(r, listOf(reaction))
        val mixture = Mixture(r, 1.0, 298.15, mapOf("eq:water" to 1000.0, "eq:b" to 1.0))

        engine.simulate(mixture, 1.0, 400)

        // Starting from pure B, the reverse direction must produce A until the same 1:3 ratio.
        assertEquals(0.25, mixture.amount("eq:a"), 0.01)
        assertEquals(0.75, mixture.amount("eq:b"), 0.01)
    }

    @Test
    fun `a sparingly soluble salt precipitates until nearly all the ion is gone`() {
        val r = SpeciesRegistry()
        r.register(
            Species("eq:water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3, solventGroup = "water")
        )
        // Real standard entropies, so the solubility product is not authored but derived.
        r.register(Species("eq:cu2", "Copper(II) ion", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0, entropy = -99.6))
        r.register(Species("eq:oh", "Hydroxide ion", Formula.parse("OH^-"), Phase.AQUEOUS, -230_015.0, 1.0, entropy = 10.75))
        r.register(
            Species("eq:cuoh2", "Copper(II) hydroxide", Formula.parse("Cu(OH)2"), Phase.SOLID, -449_800.0, 98.0, entropy = 108.4)
        )
        val reaction = Reaction(
            "eq:cuoh2_precipitation",
            reactants = mapOf("eq:cu2" to 1.0, "eq:oh" to 2.0),
            products = mapOf("eq:cuoh2" to 1.0),
            activationEnergy = 10_000.0,
            preExponential = 1.0e6,
            reversible = true,
        )
        val engine = ReactionEngine(r, listOf(reaction))
        val mixture = Mixture(
            r, 1.0, 298.15,
            mapOf("eq:water" to 1000.0, "eq:cu2" to 1.0, "eq:oh" to 2.0),
        )

        engine.simulate(mixture, 1.0, 2000)

        // The derived K is ~1/Ksp(Cu(OH)2) ~ 1e19, so precipitation is strongly favoured. The
        // third-order rate law (r ~ [Cu][OH]^2) makes the last trace approach slowly, so assert
        // the bulk outcome rather than a specific residual.
        assertTrue(mixture.amount("eq:cu2") < 0.05, "copper should be nearly all precipitated, was ${mixture.amount("eq:cu2")}")
        assertEquals(2.0 * mixture.amount("eq:cu2"), mixture.amount("eq:oh"), 1e-6, "OH^- tracks Cu^2+ at 2:1")
        assertTrue(mixture.amount("eq:cuoh2") > 0.95, "most copper should be in the solid phase")
        assertEquals(0.0, mixture.charge(), 1e-9)
    }
}
