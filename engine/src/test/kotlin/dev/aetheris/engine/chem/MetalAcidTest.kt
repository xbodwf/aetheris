package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Acid attacking a metal is the chemistry behind a bucket that corrodes: the container's own substance is a
 * reactant, so "does this eat through" is answered by the same engine that answers everything else.
 *
 * The test worth reading is the last one. Copper has an aqueous ion in the catalogue and sits below hydrogen
 * in the reactivity series, so the tempting "generate a reaction for every metal with an ion" rule would
 * invent chemistry that does not happen - which is why the metals are listed by hand.
 */
class MetalAcidTest {

    private fun registry(): SpeciesRegistry {
        val registry = SpeciesRegistry()
        registry.register(
            Species(
                "test:iron", "Iron", Formula.parse("Fe"), Phase.SOLID, 0.0, 25.1,
            ),
        )
        registry.register(
            Species(
                "test:copper", "Copper", Formula.parse("Cu"), Phase.SOLID, 0.0, 24.4,
            ),
        )
        registry.register(
            Species(
                "test:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 1.0,
            ),
        )
        registry.register(
            Species(
                "test:iron_2_ion", "Iron(II) ion", Formula.parse("Fe^2+"), Phase.AQUEOUS,
                -89_100.0, 25.0, entropy = -105.6,
            ),
        )
        registry.register(
            Species(
                "test:copper_2_ion", "Copper(II) ion", Formula.parse("Cu^2+"), Phase.AQUEOUS,
                64_900.0, 25.0, entropy = -99.6,
            ),
        )
        registry.register(
            Species(
                SpeciesRegistry.H2, "Hydrogen", Formula.parse("H2"), Phase.GAS, 0.0, 28.84,
            ),
        )
        registry.register(
            Species(
                SpeciesRegistry.H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID,
                -285_830.0, 75.3, solventGroup = "water",
            ),
        )
        return registry
    }

    private fun template(metals: Map<String, Int>) = MetalAcidTemplate(metals = metals)

    @Test
    fun `iron is attacked and the reaction balances`() {
        val registry = registry()
        val reactions = template(mapOf("test:iron" to 2)).instantiate(registry)

        val reaction = reactions.single()
        assertEquals(1.0, reaction.reactants["test:iron"])
        assertEquals(2.0, reaction.reactants["test:hydrogen_ion"], "two protons per Fe^2+")
        assertEquals(1.0, reaction.products["test:iron_2_ion"])
        assertEquals(1.0, reaction.products["test:H2"] ?: reaction.products[SpeciesRegistry.H2])

        // The engine's own check: element AND charge balance, not just the coefficients looking tidy.
        ReactionEngine(registry, reactions)

        // Iron dissolving in acid is exothermic, so the sign must come out negative.
        assertTrue(reaction.standardEnthalpy(registry) < 0.0)
    }

    /**
     * The test that the first version of this reaction needed and did not have.
     *
     * It asserted only that the acid was *eventually* consumed, which a rate a million times too fast passes
     * just as happily as a correct one. Pinning the pace is what makes "dilute acid stains a bucket, a
     * concentrated one eats through it" a claim rather than a hope - and the pace is genuinely easy to get
     * wrong here, because the driving term is a concentration in mol/m^3 squared, and because a gaseous
     * product used to size the whole rate by a headspace that is nearly nothing in a full cell.
     *
     * The shape asserted is a **half-life**, not a completion time: the law is second order in H+, so it
     * starts briskly and then has a long tail, exactly like the precipitation kinetics. Demanding "spent
     * within N seconds" would be asking for a curve this rate law cannot have.
     */
    @Test
    fun `a litre of 1 M acid takes minutes rather than an instant`() {
        val registry = registry()
        val reactor = ReactionEngine(registry, template(mapOf("test:iron" to 2)).instantiate(registry))
        val vessel = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(SpeciesRegistry.H2O_LIQUID to 55.5, "test:hydrogen_ion" to 1.0, "test:iron" to 10.0),
        )
        fun acid() = vessel.amount("test:hydrogen_ion")

        reactor.simulate(vessel, 1.0, 5)
        assertTrue(acid() > 0.9, "five seconds should barely dent a litre of 1 M acid, but ${acid()} was left")

        // Half of it gone by about two minutes: fast enough to watch, slow enough to see happening.
        reactor.simulate(vessel, 1.0, 115)
        assertTrue(acid() in 0.35..0.65, "about half should be spent after two minutes, but ${acid()} was left")

        // And the tail: nine tenths by half an hour, which is the second-order curve rather than a stall.
        reactor.simulate(vessel, 1.0, 1_680)
        assertTrue(acid() < 0.1, "half an hour should leave little acid, but ${acid()} was left")
    }

    @Test
    fun `acid is consumed, not regenerated, so a dilute acid simply neutralises`() {
        val registry = registry()
        val reactions = template(mapOf("test:iron" to 2)).instantiate(registry)
        val engine = ReactionEngine(registry, reactions)

        // A litre of 1 M acid over plenty of iron: the acid is the limiting reagent.
        val vessel = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(SpeciesRegistry.H2O_LIQUID to 55.5, "test:hydrogen_ion" to 1.0, "test:iron" to 10.0),
        )
        engine.simulate(vessel, 1.0, 3_600)

        // Most of it, not all: the law is second order in H+, so the last few percent take their time.
        assertTrue(vessel.amount("test:hydrogen_ion") < 0.05, "the acid should be nearly spent")
        // Half a mole of iron per mole of protons, and no more.
        assertEquals(0.5, vessel.amount("test:iron_2_ion"), 0.02)
        assertTrue(vessel.amount("test:iron") > 9.0, "most of the iron survives a dilute acid")
    }

    @Test
    fun `a metal with no matching aqueous ion is skipped rather than guessed at`() {
        val registry = registry()
        registry.register(
            Species("test:titanium", "Titanium", Formula.parse("Ti"), Phase.SOLID, 0.0, 25.0),
        )
        assertTrue(template(mapOf("test:iron" to 2, "test:titanium" to 3)).instantiate(registry).size == 1)
    }

    @Test
    fun `copper is left alone because the list is authored, not derived`() {
        val registry = registry()

        // The trap: copper has an aqueous ion, so a generated rule would happily produce Cu + 2H+ -> Cu^2+ +
        // H2, which does not happen. The template only builds what it is told to.
        assertNotNull(registry.byFormula(Formula.parse("Cu^2+"), Phase.AQUEOUS).firstOrNull())
        val reactions = template(mapOf("test:iron" to 2)).instantiate(registry)
        assertTrue(reactions.none { it.reactants.containsKey("test:copper") })
        assertNull(reactions.firstOrNull { it.id.endsWith("test:copper") })
    }
}
