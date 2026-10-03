package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactionEngineTest {

    private val registry = SpeciesRegistry.defaults()
    private val template = CombustionTemplate()
    private val reactions = template.instantiate(registry)
    private val engine = ReactionEngine(registry, reactions)

    private val methaneId = "${template.id}/${SpeciesRegistry.CH4}"
    private val carbonMonoxideId = "${template.id}/${SpeciesRegistry.CO}"
    private val hydrogenId = "${template.id}/${SpeciesRegistry.H2}"

    @Test
    fun `combustion template covers fuels and skips products`() {
        assertNotNull(engine.reaction(methaneId))
        assertNotNull(engine.reaction(carbonMonoxideId))
        assertNotNull(engine.reaction(hydrogenId))
        // CO2 and H2O are combustion products, not fuels, so no reaction is generated for them.
        assertNull(engine.reaction("${template.id}/${SpeciesRegistry.CO2}"))
        assertNull(engine.reaction("${template.id}/${SpeciesRegistry.H2O_GAS}"))
    }

    @Test
    fun `methane reaction has physical stoichiometry and order`() {
        val reaction = engine.reaction(methaneId)!!
        assertEquals(2.0, reaction.reactants[SpeciesRegistry.O2]!!, 1e-9)
        assertEquals(1.0, reaction.reactants[SpeciesRegistry.CH4]!!, 1e-9)
        assertEquals(1.0, reaction.products[SpeciesRegistry.CO2]!!, 1e-9)
        assertEquals(2.0, reaction.products[SpeciesRegistry.H2O_GAS]!!, 1e-9)
        // Regression: coefficients must not be inflated by a common factor, or the rate law
        // order balloons and the reaction becomes unphysically fast.
        assertEquals(3.0, reaction.order, 1e-9)
    }

    @Test
    fun `carbon monoxide and hydrogen reactions are balanced with fractional oxygen`() {
        val co = engine.reaction(carbonMonoxideId)!!
        assertEquals(0.5, co.reactants[SpeciesRegistry.O2]!!, 1e-9)
        assertEquals(1.5, co.order, 1e-9)

        val h2 = engine.reaction(hydrogenId)!!
        assertEquals(0.5, h2.reactants[SpeciesRegistry.O2]!!, 1e-9)
        assertEquals(1.0, h2.products[SpeciesRegistry.H2O_GAS]!!, 1e-9)
    }

    @Test
    fun `engine enthalpy agrees with Hess law`() {
        val reaction = engine.reaction(methaneId)!!
        assertEquals(reaction.standardEnthalpy(registry), engine.enthalpyOf(methaneId), 1e-9)
        assertTrue(engine.enthalpyOf(methaneId) < 0.0, "combustion must be exothermic")
    }

    @Test
    fun `a step burns fuel, warms the mixture and conserves every element`() {
        val mixture = Mixture(
            registry, 1.0, 1000.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0, SpeciesRegistry.N2 to 7.52),
        )
        val elementsBefore = mixture.elementTotals()

        val report = engine.step(mixture, 0.1)

        assertTrue(report.reacted)
        assertTrue(report.extents.getValue(methaneId) > 0.0)
        assertTrue(mixture.amount(SpeciesRegistry.CH4) < 1.0)
        assertTrue(mixture.amount(SpeciesRegistry.O2) < 2.0)
        assertTrue(mixture.amount(SpeciesRegistry.CO2) > 0.0)
        assertTrue(mixture.amount(SpeciesRegistry.H2O_GAS) > 0.0)
        // Exothermic: the mixture heats up and the step releases heat.
        assertTrue(mixture.temperature > 1000.0, "expected warming, got ${mixture.temperature} K")
        assertTrue(report.heatReleased > 0.0)

        val elementsAfter = mixture.elementTotals()
        for (element in elementsBefore.keys) {
            assertEquals(elementsBefore.getValue(element), elementsAfter.getValue(element), 1e-9, "element $element")
        }
        assertEquals(0.0, mixture.charge(), 1e-9)
    }

    @Test
    fun `extent is clamped to the limiting reactant`() {
        val mixture = Mixture(
            registry, 1.0, 1000.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 1.0),
        )
        val reaction = engine.reaction(methaneId)!!
        // O2 runs out after 0.5 mol of extent (coefficient 2).
        assertEquals(0.5, reaction.limitingExtent(mixture), 1e-9)

        // A deliberately huge dt would overshoot; the engine must clamp instead.
        val report = engine.step(mixture, 1000.0)
        assertEquals(0.5, report.extents.getValue(methaneId), 1e-9)
        assertEquals(0.5, mixture.amount(SpeciesRegistry.CH4), 1e-9)
        assertEquals(0.0, mixture.amount(SpeciesRegistry.O2), 1e-9)
        assertEquals(0.5, mixture.amount(SpeciesRegistry.CO2), 1e-9)
        assertEquals(1.0, mixture.amount(SpeciesRegistry.H2O_GAS), 1e-9)
    }

    @Test
    fun `no fuel reacts without oxygen`() {
        val mixture = Mixture(registry, 1.0, 1000.0, mapOf(SpeciesRegistry.CH4 to 1.0))
        val report = engine.step(mixture, 1.0)
        assertFalse(report.reacted)
        assertEquals(0.0, report.extents.getValue(methaneId), 1e-9)
        assertEquals(1000.0, mixture.temperature, 1e-6)
        assertEquals(1.0, mixture.amount(SpeciesRegistry.CH4), 1e-9)
    }

    @Test
    fun `adiabatic temperature is clamped by the engine config`() {
        // Ignition needs a hot mixture: at 300 K the Arrhenius rate is negligible, so start
        // hot and cap the temperature well below the ~3500 K full combustion would reach.
        val clamped = ReactionEngine(registry, reactions, EngineConfig(maxTemperature = 1500.0))
        val mixture = Mixture(
            registry, 1.0, 1000.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0, SpeciesRegistry.N2 to 7.52),
        )
        val report = clamped.step(mixture, 1000.0)
        assertTrue(report.reacted)
        assertTrue(report.temperatureClamped)
        assertEquals(1500.0, report.temperatureAfter, 1e-9)
        assertEquals(1500.0, mixture.temperature, 1e-9)
    }

    @Test
    fun `simulate returns one report per step`() {
        val mixture = Mixture(
            registry, 1.0, 1000.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0, SpeciesRegistry.N2 to 7.52),
        )
        val reports = engine.simulate(mixture, 0.05, 5)
        assertEquals(5, reports.size)
        assertTrue(reports.first().reacted)
        // The exothermic runaway can consume all the fuel within one step, so later steps may
        // legitimately report no reaction; the mixture must still have ended up hotter.
        assertTrue(reports.first().temperatureAfter < reports.last().temperatureAfter)
    }

    @Test
    fun `fuel is eventually exhausted over a long simulation`() {
        val mixture = Mixture(
            registry, 1.0, 1000.0,
            mapOf(SpeciesRegistry.CH4 to 0.1, SpeciesRegistry.O2 to 0.2),
        )
        engine.simulate(mixture, 0.5, 200)
        assertTrue(mixture.amount(SpeciesRegistry.CH4) < 1e-6, "methane should be consumed")
        assertTrue(mixture.amount(SpeciesRegistry.O2) < 1e-6, "oxygen should be consumed")
    }

    @Test
    fun `rate law is Arrhenius and slower when cold`() {
        val reaction = engine.reaction(methaneId)!!
        val hot = Mixture(
            registry, 1.0, 1500.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0),
        )
        val cold = Mixture(
            registry, 1.0, 600.0,
            mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0),
        )
        assertTrue(reaction.ratePerVolume(hot) > reaction.ratePerVolume(cold))
        assertTrue(reaction.ratePerVolume(cold) > 0.0)
    }
}
