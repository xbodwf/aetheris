package dev.aetheris.engine.data

import dev.aetheris.engine.chem.CombustionTemplate
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Assembles the catalogue exactly the way the mod does at runtime: the NASA gas set, the curated
 * aqueous slice and the NIST condensed phases in one registry, then builds the engine.
 *
 * This is the test that catches duplicate ids between sources and any reaction template that
 * fails element/charge balance once all three are present.
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
    fun `nasa, aqueous and nist combine into one valid engine`() {
        val files = listOf("nasa_gas.json", "aqueous.json", "nist.json").map { resource(it) }
        assumeTrue(files.all { it != null }, "species data not present")

        val registry = SpeciesRegistry()
        for (file in files) SpeciesCodec.load(file!!.readText(), registry)

        // Building the engine validates every generated reaction's element AND charge balance.
        val reactions = CombustionTemplate().instantiate(registry) +
            WetChemistry.precipitation.instantiate(registry)
        ReactionEngine(registry, reactions)

        // Gas vapours and condensed metals coexist under separate ids and correct phases.
        assertEquals(Phase.GAS, registry.require("aetheris:al").phase)
        assertEquals(Phase.SOLID, registry.require("nist:aluminium").phase)
        assertTrue(registry.all.size > 700, "expected the full combined catalogue")
    }
}
