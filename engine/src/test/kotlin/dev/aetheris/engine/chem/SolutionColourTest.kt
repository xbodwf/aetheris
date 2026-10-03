package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Colour presence is derived from the periodic table (d-shell filling), not hand-authored. */
class SolutionColourTest {

    @Test
    fun `partially filled d shell means coloured, d0 and d10 mean colourless`() {
        assertTrue(SolutionColour.isDdColoured("Cu", 2))   // d9, blue
        assertTrue(SolutionColour.isDdColoured("Fe", 3))   // d5
        assertTrue(SolutionColour.isDdColoured("Mn", 2))   // d5
        assertTrue(SolutionColour.isDdColoured("Cr", 3))   // d3
        assertFalse(SolutionColour.isDdColoured("Zn", 2))  // d10
        assertFalse(SolutionColour.isDdColoured("Cu", 1))  // d10
        assertFalse(SolutionColour.isDdColoured("Sc", 3))  // d0
        assertFalse(SolutionColour.isDdColoured("Ti", 4))  // d0
        assertFalse(SolutionColour.isDdColoured("Na", 1))  // main group
        assertFalse(SolutionColour.isDdColoured("S", -2))  // anion
    }

    @Test
    fun `expectsColour reads the metal out of a formula`() {
        val copper = Species("c:cu2", "Copper(II)", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0)
        val sulfate = Species("c:so4", "Sulfate", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0)
        val zinc = Species("c:zn2", "Zinc(II)", Formula.parse("Zn^2+"), Phase.AQUEOUS, -153_600.0, 25.0)
        assertTrue(SolutionColour.expectsColour(copper))
        assertFalse(SolutionColour.expectsColour(sulfate))
        assertFalse(SolutionColour.expectsColour(zinc))
    }

    @Test
    fun `missingColour lists only the coloured ions that still lack a hue`() {
        val registry = SpeciesRegistry()
        registry.register(Species("c:cu2", "Copper(II)", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0))
        registry.register(Species("c:zn2", "Zinc(II)", Formula.parse("Zn^2+"), Phase.AQUEOUS, -153_600.0, 25.0))
        registry.register(Species("c:so4", "Sulfate", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0))

        val missing = SolutionColour.missingColour(registry)
        assertEquals(listOf("c:cu2"), missing.map { it.id })

        // Supplying the hue clears the warning; the rule never guesses one.
        val withHue = SpeciesRegistry()
        withHue.register(Species("c:cu2", "Copper(II)", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0, color = 0x1E90FF))
        assertTrue(SolutionColour.missingColour(withHue).isEmpty())
    }
}
