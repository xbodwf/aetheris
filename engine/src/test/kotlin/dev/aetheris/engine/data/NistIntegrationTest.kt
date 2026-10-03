package dev.aetheris.engine.data

import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.SpeciesRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The NIST-imported condensed phases load and carry sane thermochemistry. Values are checked
 * against the NIST Chemistry WebBook (e.g. lime dHf = -634.92 kJ/mol, S = 38.1 J/mol/K).
 */
class NistIntegrationTest {

    private fun locate(): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "common/src/main/resources/data/aetheris/species/nist.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `nist condensed phases load with expected values`() {
        val file = locate()
        assumeTrue(file != null, "NIST data not present; run tools/convert_nist.py")
        val registry = SpeciesRegistry()
        SpeciesCodec.load(file!!.readText(), registry)

        val lime = registry.require("nist:calcium_oxide")
        assertEquals(Phase.SOLID, lime.phase)
        assertEquals(-634_920.0, lime.formationEnthalpy, 2_000.0)
        assertEquals(38.1, lime.entropy, 0.5)
        assertTrue(lime.heatCapacity > 0.0)

        val cuoh2 = registry.require("nist:copper_2_hydroxide")
        assertEquals(-450_370.0, cuoh2.formationEnthalpy, 2_000.0)
        assertEquals(108.43, cuoh2.entropy, 0.5)
    }
}
