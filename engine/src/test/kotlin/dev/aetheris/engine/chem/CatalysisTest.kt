package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A catalyst changes how fast a reaction goes, not how far. It lowers the effective activation
 * energy while present and is left untouched, so the equilibrium point (K) is unchanged.
 */
class CatalysisTest {

    private val catalyst = "cat:acid"

    private fun registry(): SpeciesRegistry {
        val r = SpeciesRegistry()
        r.register(
            Species("cat:water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3, solventGroup = "water")
        )
        r.register(Species("cat:a", "A", Formula.parse("C2H4O2"), Phase.AQUEOUS, 0.0, 50.0))
        r.register(Species("cat:b", "B", Formula.parse("C2H4O2"), Phase.AQUEOUS, -5_000.0, 50.0))
        r.register(Species(catalyst, "Catalyst", Formula.parse("H2SO4"), Phase.AQUEOUS, -909_270.0, 100.0))
        return r
    }

    private fun reaction(registry: SpeciesRegistry, reversible: Boolean = false) = Reaction(
        id = "cat:isomerisation",
        reactants = mapOf("cat:a" to 1.0),
        products = mapOf("cat:b" to 1.0),
        activationEnergy = 50_000.0,
        preExponential = 1.0e6,
        reversible = reversible,
        catalyst = catalyst,
        catalystActivationReduction = 20_000.0,
    )

    @Test
    fun `catalyst accelerates the reaction without being consumed`() {
        val r = registry()
        val rx = reaction(r)
        val bare = Mixture(r, 1.0, 400.0, mapOf("cat:water" to 1000.0, "cat:a" to 1.0))
        val catalysed = Mixture(r, 1.0, 400.0, mapOf("cat:water" to 1000.0, "cat:a" to 1.0, catalyst to 0.01))

        assertTrue(rx.ratePerVolume(catalysed) > rx.ratePerVolume(bare), "catalyst must speed the reaction up")

        val engine = ReactionEngine(r, listOf(rx))
        engine.step(catalysed, 1.0)
        assertEquals(0.01, catalysed.amount(catalyst), 1e-12, "a catalyst is not consumed")
    }

    @Test
    fun `catalyst does not move the equilibrium position`() {
        val r = registry()
        // dH = -5000 both ways; the point is that K is identical with and without the catalyst.
        val rx = reaction(r, reversible = true)
        val engine = ReactionEngine(r, listOf(rx))

        // Hot enough that even the uncatalysed path reaches equilibrium within the run.
        val bare = Mixture(r, 1.0, 1000.0, mapOf("cat:water" to 1000.0, "cat:a" to 1.0))
        val catalysed = Mixture(r, 1.0, 1000.0, mapOf("cat:water" to 1000.0, "cat:a" to 1.0, catalyst to 0.01))
        // Small dt keeps the explicit reversible integrator stable even with the fast catalysed rate.
        engine.simulate(bare, 0.02, 4000)
        engine.simulate(catalysed, 0.02, 4000)

        assertEquals(bare.amount("cat:b"), catalysed.amount("cat:b"), 0.01, "catalyst must not shift equilibrium")
    }
}
