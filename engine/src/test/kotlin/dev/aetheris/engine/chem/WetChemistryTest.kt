package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The target scenario, at engine level: a copper sulfate solution meets a source of iron(II)
 * and hydroxide; the copper precipitates as copper(II) hydroxide while iron(II) sulfate stays
 * dissolved, and the solution colour changes with it.
 *
 * Solutions are modelled as dissociated ions, which is why no "double displacement template" is
 * needed: put Cu^2+, Fe^2+, SO4^2- and OH^- in one vessel and the only favourable reaction,
 * Cu^2+ + 2 OH^- -> Cu(OH)2(s), runs on its own. The spectator ions fall out for free.
 */
class WetChemistryTest {

    private object Ids {
        const val WATER = "demo:water"
        const val CU2 = "demo:copper_2_ion"
        const val SO4 = "demo:sulfate_ion"
        const val FE2 = "demo:iron_2_ion"
        const val OH = "demo:hydroxide_ion"
        const val CUOH2 = "demo:copper_2_hydroxide"
    }

    private val blue = 0xFF1E90FF.toInt()   // Cu^2+(aq)
    private val green = 0xFF90EE90.toInt()  // Fe^2+(aq)

    private fun registry(): SpeciesRegistry {
        val r = SpeciesRegistry()
        r.register(
            Species(Ids.WATER, "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3, solventGroup = "water")
        )
        r.register(Species(Ids.CU2, "Copper(II) ion", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0, color = blue))
        r.register(Species(Ids.SO4, "Sulfate ion", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0))
        r.register(Species(Ids.FE2, "Iron(II) ion", Formula.parse("Fe^2+"), Phase.AQUEOUS, -89_100.0, 25.0, color = green))
        r.register(Species(Ids.OH, "Hydroxide ion", Formula.parse("OH^-"), Phase.AQUEOUS, -230_015.0, 1.0))
        r.register(Species(Ids.CUOH2, "Copper(II) hydroxide", Formula.parse("Cu(OH)2"), Phase.SOLID, -449_800.0, 98.0))
        return r
    }

    /** Cu^2+ + 2 OH^- -> Cu(OH)2(s): ions and charge both balance. */
    private fun precipitation(registry: SpeciesRegistry): Reaction = Reaction(
        id = "demo:copper_hydroxide_precipitation",
        reactants = mapOf(Ids.CU2 to 1.0, Ids.OH to 2.0),
        products = mapOf(Ids.CUOH2 to 1.0),
        activationEnergy = 10_000.0,
        preExponential = 1.0e3,
    )

    @Test
    fun `copper sulfate solution meets iron hydroxide and copper precipitates`() {
        val registry = registry()
        val engine = ReactionEngine(registry, listOf(precipitation(registry)))

        // The setblock-ed vessel: copper sulfate dissolved in water.
        val vessel = Mixture(
            registry, volume = 1.0, temperatureK = 298.15,
            composition = mapOf(Ids.WATER to 1000.0, Ids.CU2 to 1.0, Ids.SO4 to 1.0),
        )
        assertEquals(blue, vessel.solutionColor(), "copper sulfate solution is blue")

        // The neighbouring reagent arrives and mixes in: iron(II) and hydroxide.
        vessel.add(Ids.FE2, 1.0)
        vessel.add(Ids.OH, 2.0)
        val elementsBefore = vessel.elementTotals()

        val report = engine.step(vessel, 1.0)

        assertTrue(report.reacted)
        // Copper and hydroxide are consumed; the precipitate appears as its own solid phase.
        assertEquals(0.0, vessel.amount(Ids.CU2), 1e-9)
        assertEquals(0.0, vessel.amount(Ids.OH), 1e-9)
        assertEquals(1.0, vessel.amount(Ids.CUOH2), 1e-9)
        val solid = vessel.phaseStates.first { it.kind == PhaseKind.SOLID }
        assertEquals(1.0, solid.amount(Ids.CUOH2), 1e-9)
        assertEquals(1.0, vessel.activity(Ids.CUOH2), 1e-9)

        // Spectators stay dissolved: the vessel now holds iron(II) sulfate in solution.
        assertEquals(1.0, vessel.amount(Ids.FE2), 1e-9)
        assertEquals(1.0, vessel.amount(Ids.SO4), 1e-9)

        // Colour follows the ions that remain dissolved: blue -> pale green.
        val after = vessel.solutionColor()
        assertNotEquals(blue, after)
        val actualRgb = after!! and 0xFFFFFF
        val expectedRgb = green and 0xFFFFFF
        for (shift in intArrayOf(16, 8, 0)) {
            assertEquals(
                ((expectedRgb shr shift) and 0xFF).toDouble(),
                ((actualRgb shr shift) and 0xFF).toDouble(),
                2.0,
                "colour channel at shift $shift",
            )
        }

        // The invariants still hold across the reaction.
        assertEquals(0.0, vessel.charge(), 1e-9)
        for ((element, moles) in elementsBefore) {
            assertEquals(moles, vessel.elementTotals().getValue(element), 1e-9, "element $element")
        }
    }
}
