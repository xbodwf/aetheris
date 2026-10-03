package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The text an info mod would show for a solution: solutes/precipitates, solvent omitted. */
class SolutionNamesTest {

    private val registry = SpeciesRegistry.defaults()

    @Test
    fun `contains solutes and solids but not the water solvent`() {
        val mixture = Mixture(
            registry, 1.0, 298.15,
            mapOf(
                SpeciesRegistry.H2O_LIQUID to 1000.0,
                "aetheris:iron" to 0.2,
            ),
        )
        val text = SolutionNames.describe(mixture)
        assertTrue(text.contains("Fe"), "should mention the solute, got '$text'")
        assertTrue(!text.contains("H2O"), "should omit the water solvent, got '$text'")
    }

    @Test
    fun `describes the largest solute first and says empty for pure solvent`() {
        val mixture = Mixture(
            registry, 1.0, 298.15,
            mapOf(SpeciesRegistry.H2O_LIQUID to 1000.0, SpeciesRegistry.CO2 to 0.1, "aetheris:iron" to 2.0),
        )
        val contents = SolutionNames.contents(mixture)
        assertEquals("Fe", contents.first().formula)
        assertEquals(2.0, contents.first().moles, 1e-9)

        val pure = Mixture(registry, 1.0, 298.15, mapOf(SpeciesRegistry.H2O_LIQUID to 500.0))
        assertEquals("empty", SolutionNames.describe(pure))
    }
}
