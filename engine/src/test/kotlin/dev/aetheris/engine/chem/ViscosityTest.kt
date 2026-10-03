package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Viscosity is what makes "how fast does this solution flow" a property of the substance. These tests
 * pin both halves of that: the mixing rule that turns a composition into one number, and the
 * reciprocal relationship that turns that number into a rate relative to water.
 */
class ViscosityTest {

    @Test
    fun `water flows at the reference rate`() {
        assertEquals(1.0, Viscosity.flowScale(Viscosity.WATER), 1e-12)
    }

    @Test
    fun `a thicker liquid is slower and a thinner one is faster`() {
        // Ethylene glycol, ~16 mPa*s, is about eighteen times water.
        assertEquals(1.0 / 18.0, Viscosity.flowScale(16.1e-3), 1e-3)
        // Acetone, ~0.31 mPa*s, outruns water - the rate is deliberately not capped at one.
        assertTrue(Viscosity.flowScale(0.31e-3) > 2.5, "acetone should outrun water")
    }

    @Test
    fun `a very thick liquid creeps instead of freezing`() {
        val glycerol = Viscosity.flowScale(1.412) // 1412 mPa*s
        assertEquals(Viscosity.MIN_FLOW_SCALE, glycerol, 1e-12)
        assertTrue(glycerol > 0.0, "flow must never stop entirely, or it is indistinguishable from a bug")
    }

    @Test
    fun `an uncharacterised liquid is assumed to behave like water`() {
        assertEquals(1.0, Viscosity.flowScale(0.0), 1e-12)
    }

    @Test
    fun `the mixing rule is logarithmic, not linear`() {
        val mixed = Viscosity.mixture(listOf(0.1 to 1.412, 0.9 to 8.9e-4))!!
        val linear = 0.1 * 1.412 + 0.9 * 8.9e-4

        assertTrue(mixed > 8.9e-4, "a little glycerol should still thicken the water")
        assertTrue(mixed < 1.412, "and the mixture must sit below its thickest component")
        assertTrue(
            mixed < linear / 10.0,
            "a linear average would over-thicken badly: got $mixed, linear would be $linear",
        )
    }

    @Test
    fun `components without a tabulated viscosity are excluded rather than assumed`() {
        // Half water, half something unknown: the answer is water's, not water halved or doubled.
        assertEquals(8.9e-4, Viscosity.mixture(listOf(0.5 to 8.9e-4, 0.5 to 0.0))!!, 1e-15)
        assertNull(Viscosity.mixture(listOf(0.5 to 0.0)), "nothing known means no answer")
    }

    @Test
    fun `a cell reports the flow rate of the liquid actually in it`() {
        val registry = SpeciesRegistry()
        val water = registry.register(
            Species(
                "test:water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3,
                solventGroup = "water", viscosityPaS = 8.9e-4,
            ),
        )
        val glycerol = registry.register(
            Species(
                "test:glycerol", "Glycerol", Formula.parse("C3H8O3"), Phase.LIQUID, -668_600.0, 218.9,
                solventGroup = "water", viscosityPaS = 1.412,
            ),
        )

        // A full block of water: 1 m^3, so ~55.5 kmol.
        val pure = Mixture(registry, 1.0, 298.15, mapOf(water.id to 55_500.0))
        assertEquals(1.0, pure.flowScale(), 1e-9, "water is the reference")

        val thick = Mixture(registry, 1.0, 298.15, mapOf(glycerol.id to 13_700.0))
        assertEquals(Viscosity.MIN_FLOW_SCALE, thick.flowScale(), 1e-12)

        // A glycerol/water mixture is slower than water and faster than glycerol - and much closer to
        // water than the mole ratio alone suggests, which is the point of the log mixing rule.
        val mixed = Mixture(
            registry, 1.0, 298.15,
            mapOf(water.id to 27_750.0, glycerol.id to 6_850.0),
        )
        assertTrue(
            mixed.flowScale() in Viscosity.MIN_FLOW_SCALE..1.0,
            "a mixture should land between its components, was ${mixed.flowScale()}",
        )
        assertTrue(
            mixed.flowScale() > 0.1,
            "20 mol% glycerol must not throttle the cell to a crawl, was ${mixed.flowScale()}",
        )
    }
}
