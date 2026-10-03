package dev.aetheris.engine.data

import dev.aetheris.engine.chem.SpeciesLookup
import dev.aetheris.engine.chem.SpeciesRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Whether the formulas a chemist types actually resolve - against the **shipped** catalogue, not a registry a
 * test invented.
 *
 * That distinction is the whole point of the test. The lookup logic can be perfectly correct and still useless
 * if the data has no sodium ion, and no amount of compiling reveals it. Every formula below is one somebody
 * would reasonably write for a jar off the shelf.
 */
class SpeciesLookupDataTest {

    private fun resource(name: String): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "common/src/main/resources/data/aetheris/species/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun catalogue(): SpeciesRegistry? {
        val files = listOf("nasa_gas.json", "aqueous.json", "liquids.json", "nist.json").map { resource(it) }
        assumeTrue(files.all { it != null }, "species data not present")
        val registry = SpeciesRegistry()
        for (file in files) SpeciesCodec.load(file!!.readText(), registry)
        return registry
    }

    private fun lookup(registry: SpeciesRegistry, name: String) = SpeciesLookup.contribution(registry, name)

    @Test
    fun `acids, bases and salts written as formulas resolve to their ions`() {
        val registry = catalogue() ?: return

        assertEquals(
            mapOf("aetheris:hydrogen_ion" to 1.0, "aetheris:chloride_ion" to 1.0),
            lookup(registry, "HCl"),
            "hydrogen chloride is a gas species in the catalogue, but a solute called HCl is hydrochloric acid",
        )
        assertEquals(
            mapOf("aetheris:sodium_ion" to 1.0, "aetheris:hydroxide_ion" to 1.0),
            lookup(registry, "NaOH"),
        )
        assertEquals(
            mapOf("aetheris:copper_2_ion" to 1.0, "aetheris:sulfate_ion" to 1.0),
            lookup(registry, "CuSO4"),
        )
        assertEquals(
            mapOf("aetheris:iron_2_ion" to 1.0, "aetheris:chloride_ion" to 2.0),
            lookup(registry, "FeCl2"),
        )
    }

    @Test
    fun `a substance the catalogue holds beats its decomposition`() {
        val registry = catalogue() ?: return

        // Water is a liquid and a species; it is not an acid plus a base, and the exact match knows that.
        assertEquals(mapOf("aetheris:water" to 1.0), lookup(registry, "H2O"))
        // Copper(II) hydroxide is a real precipitate, so naming it means the solid rather than two ions.
        assertEquals(mapOf("aetheris:copper_2_hydroxide" to 1.0), lookup(registry, "Cu(OH)2"))
        // And sulfuric acid is a liquid the catalogue holds, so it is *that substance* rather than two protons
        // and a sulfate. Worth stating plainly because it is a real limitation: nothing in the model makes a
        // molecular acid dissociate on its own, so a genuinely acidic solution is written as its ions. Making
        // H2SO4 mean 2H+ + SO4^2- wants a dissociation reaction, which is a chemistry addition rather than a
        // lookup rule.
        assertEquals(mapOf("aetheris:sulfuric_acid" to 1.0), lookup(registry, "H2SO4"))
    }

    @Test
    fun `ids and bare names still work, and nonsense still fails`() {
        val registry = catalogue() ?: return

        assertEquals(mapOf("aetheris:glycerol" to 1.0), lookup(registry, "glycerol"))
        assertEquals(mapOf("aetheris:glycerol" to 1.0), lookup(registry, "aetheris:glycerol"))
        assertEquals(mapOf("nist:iron" to 1.0), lookup(registry, "nist:iron"))
        assertNull(lookup(registry, "KMnO4"), "nothing in the catalogue can make permanganate")
        assertNull(lookup(registry, "not_a_species"))
    }

    @Test
    fun `media resolve by formula, and only liquids are media`() {
        val registry = catalogue() ?: return

        assertEquals("aetheris:water", SpeciesLookup.liquid(registry, "H2O")?.id)
        assertEquals("aetheris:glycerol", SpeciesLookup.liquid(registry, "glycerol")?.id)
        assertEquals(
            "aetheris:sulfuric_acid",
            SpeciesLookup.liquid(registry, "H2SO4")?.id,
            "sulfuric acid is the liquid, not the two ions it would be in water",
        )
        assertNull(SpeciesLookup.liquid(registry, "NaCl"), "a salt is not a medium")
    }
}
