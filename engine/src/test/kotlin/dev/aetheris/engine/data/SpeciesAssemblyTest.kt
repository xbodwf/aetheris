package dev.aetheris.engine.data

import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.Formula
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Assembles the catalogue exactly the way the mod does at runtime: the NASA gas set, the curated
 * aqueous slice, the viscous liquids and the NIST condensed phases in one registry, then builds the
 * engine.
 *
 * This is the test that catches duplicate ids between sources, any reaction template that fails
 * element/charge balance once all of them are present, and any record whose structural notation
 * disagrees with its own formula.
 */
class SpeciesAssemblyTest {

    private fun resource(name: String): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "common/src/main/resources/data/aetheris/species/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `nasa, aqueous, liquids and nist combine into one valid engine`() {
        val files = listOf("nasa_gas.json", "aqueous.json", "liquids.json", "nist.json")
            .map { resource(it) }
        assumeTrue(files.all { it != null }, "species data not present")

        val registry = SpeciesRegistry()
        for (file in files) SpeciesCodec.load(file!!.readText(), registry)

        // Building the engine validates every generated reaction's element AND charge balance. This list has
        // to mirror `AetherisCommon`, including the acid attack: a template whose prerequisites the shipped
        // data does not actually supply throws the moment the mod loads, and the point of doing it here is
        // that it throws here instead.
        val reactions = CombustionTemplate().instantiate(registry) +
            WetChemistry.precipitation.instantiate(registry) +
            WetChemistry.metalAcid.instantiate(registry)
        ReactionEngine(registry, reactions)

        // Acid attack skips a metal silently when its ion is missing from the catalogue, so the one rule that
        // exists is asserted by name. Without this, deleting an ion record would quietly disable the whole
        // reaction and no test would notice.
        val iron = reactions.firstOrNull { it.id == "aetheris:metal_acid/${WetChemistry.IRON}" }
        assertNotNull(iron, "the shipped data must support Fe + 2H+ -> Fe2+ + H2")
        assertNotNull(
            iron!!.products.keys.firstOrNull { registry.require(it).phase == Phase.GAS },
            "acid attacking iron has to release a gas",
        )
        assertTrue(
            registry.byFormula(Formula.parse("Cl^-"), Phase.AQUEOUS).isNotEmpty(),
            "chloride is what makes hydrochloric acid an acid that can be poured",
        )

        // Gas vapours and condensed metals coexist under separate ids and correct phases.
        assertEquals(Phase.GAS, registry.require("aetheris:al").phase)
        assertEquals(Phase.SOLID, registry.require("nist:aluminium").phase)
        assertTrue(registry.all.size > 700, "expected the full combined catalogue")

        // Viscosity survived the millipascal-second to pascal-second conversion on the way in; it is
        // what makes flow rate a property of the substance rather than a constant.
        assertEquals(8.9e-4, registry.require("aetheris:water").viscosityPaS, 1e-12)
        assertEquals(1.412, registry.require("aetheris:glycerol").viscosityPaS, 1e-9)

        // Any record that ships a structure must have one that reproduces its own formula, otherwise a
        // reaction template would be matching against a molecule that is not the substance it names.
        for (species in registry.all) {
            if (species.smiles == null) continue
            assertTrue(
                species.structureMatchesFormula(),
                "${species.id}: structure '${species.smiles}' does not reproduce ${species.formula}",
            )
        }
    }
}
