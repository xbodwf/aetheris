package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A formula is what a chemist types; ions are what the model holds. These tests are the bridge, and the cases
 * worth reading are the polyprotic one - `H2SO4` has to come out as *two* protons and one sulfate, which is
 * exactly the arithmetic a naive "one formula, one species" lookup gets wrong - and the last, where a formula
 * that cannot be balanced must fail rather than return something plausible.
 */
class FormulaSplitTest {

    private fun registry(): SpeciesRegistry {
        val registry = SpeciesRegistry()
        registry.register(
            Species("t:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 1.0),
        )
        registry.register(
            Species("t:chloride_ion", "Chloride ion", Formula.parse("Cl^-"), Phase.AQUEOUS, -167_160.0, 25.0),
        )
        registry.register(
            Species("t:sodium_ion", "Sodium ion", Formula.parse("Na^+"), Phase.AQUEOUS, -240_120.0, 25.0),
        )
        registry.register(
            Species("t:hydroxide_ion", "Hydroxide ion", Formula.parse("OH^-"), Phase.AQUEOUS, -230_000.0, 25.0),
        )
        registry.register(
            Species("t:sulfate_ion", "Sulfate ion", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0),
        )
        registry.register(
            Species("t:copper_2_ion", "Copper(II) ion", Formula.parse("Cu^2+"), Phase.AQUEOUS, 64_900.0, 25.0),
        )
        registry.register(
            Species(
                SpeciesRegistry.H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID,
                -285_830.0, 75.3, solventGroup = "water",
            ),
        )
        return registry
    }

    private fun split(formula: String, registry: SpeciesRegistry = registry()) =
        FormulaSplit.decompose(Formula.parse(formula), registry)

    @Test
    fun `a strong acid splits into its ions`() {
        val ions = split("HCl")!!
        assertEquals(1.0, ions["t:hydrogen_ion"])
        assertEquals(1.0, ions["t:chloride_ion"])
    }

    @Test
    fun `a diprotic acid takes two protons`() {
        val ions = split("H2SO4")!!
        assertEquals(2.0, ions["t:hydrogen_ion"])
        assertEquals(1.0, ions["t:sulfate_ion"])
    }

    @Test
    fun `a base and a salt split into their ions`() {
        val base = split("NaOH")!!
        assertEquals(1.0, base["t:sodium_ion"])
        assertEquals(1.0, base["t:hydroxide_ion"])

        val salt = split("CuSO4")!!
        assertEquals(1.0, salt["t:copper_2_ion"])
        assertEquals(1.0, salt["t:sulfate_ion"])
    }

    @Test
    fun `charge has to balance, so an impossible formula fails rather than guessing`() {
        // NaSO4 would need a sodium to carry twice its charge, and nothing in the catalogue can do that.
        assertNull(split("NaSO4"))
        // And something with an element the catalogue does not have at all.
        assertNull(split("XeF6"))
    }

    @Test
    fun `water reads as its own self-ionisation`() {
        // Which is real chemistry, and also the reason a formula lookup has to try an exact species *first*:
        // asked blindly, H2O is an acid plus a base, and only the exact match knows it means the liquid.
        val ions = split("H2O")!!
        assertEquals(1.0, ions["t:hydrogen_ion"])
        assertEquals(1.0, ions["t:hydroxide_ion"])
    }

    @Test
    fun `a decomposition is found for ions in either order`() {
        // The search walks candidates in a fixed order; the answer must not depend on which ion it meets first.
        val ions = split("NaCl")!!
        assertEquals(1.0, ions["t:sodium_ion"])
        assertEquals(1.0, ions["t:chloride_ion"])
    }
}
