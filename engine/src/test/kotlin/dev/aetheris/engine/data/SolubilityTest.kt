package dev.aetheris.engine.data

import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Formula
import dev.aetheris.engine.chem.FormulaSplit
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.chem.SpeciesLookup
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Solubility, and the answer to "can I make a 1 M calcium carbonate solution?".
 *
 * The answer is no, but nothing in the mod *says* no: the amount that can stay dissolved is the solubility
 * product, and the solubility product is derived from three formation energies rather than authored anywhere.
 * Marking the precipitation reaction reversible is the whole instruction, and `K = 1/Ksp` falls out of the
 * thermodynamics - which is why these tests are about *data* agreeing with a handbook rather than about code
 * enforcing a limit.
 *
 * Calcite's Ksp is 3.3e-9 at 25 C, so a litre holds about 5.7e-5 mol. Getting within a small factor of that
 * from tabulated formation enthalpies and entropies is the strongest evidence available that the number was not
 * quietly typed in somewhere.
 */
class SolubilityTest {

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

    @Test
    fun `the shipped data holds a carbonate, a calcium ion and the solid`() {
        val registry = catalogue() ?: return

        assertEquals(Phase.AQUEOUS, registry.require("aetheris:calcium_2_ion").phase)
        assertEquals(Phase.AQUEOUS, registry.require("aetheris:carbonate_ion").phase)
        assertEquals(Phase.SOLID, registry.require("aetheris:calcium_carbonate").phase)

        // A formula a player writes resolves to what they would mean by it: CaCO3 is the solid, because that
        // is a substance the catalogue holds and dropping chalk into water is exactly that.
        assertEquals(
            mapOf("aetheris:calcium_carbonate" to 1.0),
            SpeciesLookup.contribution(registry, "CaCO3"),
        )
        // The dissolved pair has no single formula - it is two ions - which is why the decomposition exists
        // and why the solid winning here is the right precedence rather than a limitation.
        assertEquals(
            mapOf("aetheris:calcium_2_ion" to 1.0, "aetheris:carbonate_ion" to 1.0),
            FormulaSplit.decompose(Formula.parse("CaCO3"), registry),
            "asked as ions, chalk is a calcium ion and a carbonate",
        )
    }

    @Test
    fun `the solubility product falls out of the formation energies`() {
        val registry = catalogue() ?: return
        val dissolution = WetChemistry.precipitation.instantiate(registry)
            .single { it.products.containsKey("aetheris:calcium_carbonate") }

        // Written as precipitation, so its K is 1/Ksp.
        val ksp = 1.0 / dissolution.equilibriumConstant(registry, Constants.T0)

        // Calcite: 3.3e-9. Thermodynamic data good to a few kJ/mol cannot do better than a factor of a few,
        // and hitting that range is what proves nothing was hardcoded.
        assertTrue(ksp in 1e-9..1e-8) { "derived Ksp was $ksp, expected the 1e-9 order of calcite" }

        // The consequence in the units a player cares about: moles per litre of a saturated solution.
        val saturated = sqrt(ksp)
        assertTrue(saturated in 2e-5..2e-4) { "saturated solubility was $saturated mol/L" }
    }

    @Test
    fun `a solution a thousand times past saturation throws almost all of it out`() {
        val registry = catalogue() ?: return
        val engine = ReactionEngine(registry, WetChemistry.precipitation.instantiate(registry))

        // One litre of water with a mole each of calcium and carbonate. Charge balances, and it is about four
        // orders of magnitude past what calcite allows.
        val cell = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(
                WetChemistry.WATER to 55.5,
                WetChemistry.CALCIUM_2_ION to 1.0,
                WetChemistry.CARBONATE_ION to 1.0,
            ),
        )

        engine.simulate(cell, 1.0, 3_600)

        val dissolved = cell.amount(WetChemistry.CALCIUM_2_ION)
        assertTrue(dissolved < 0.01) { "expected the excess to precipitate, but $dissolved mol stayed dissolved" }
        assertTrue(cell.amount(WetChemistry.CALCIUM_CARBONATE) > 0.99) { "the solid should hold nearly all of it" }
        // Charge is conserved by the pair leaving together.
        assertEquals(
            cell.amount(WetChemistry.CALCIUM_2_ION),
            cell.amount(WetChemistry.CARBONATE_ION),
            1e-9,
        )
    }

    @Test
    fun `a lump of chalk barely dissolves, which is what sparingly soluble means in practice`() {
        val registry = catalogue() ?: return
        val engine = ReactionEngine(registry, WetChemistry.precipitation.instantiate(registry))

        // Put solid chalk in pure water. The equilibrium says about 7e-5 mol/L can dissolve - the previous
        // tests check that ceiling - but the *rate* is a different story, and the story is worth understanding
        // rather than tuning away.
        //
        // A reversible reaction has one rate constant for both directions: kReverse is kForward/K, with K here
        // about 2e8. That is what makes the equilibrium exact rather than approximate, and it also means an
        // approach from the saturated side is K times slower than an approach from the supersaturated side.
        // Precipitation out of a solution four orders of magnitude past saturation is watchable in under a
        // minute; dissolving a lump up to saturation takes days. No single rate constant can make both quick,
        // and that is not a defect in the engine - it is why anybody calls chalk insoluble.
        val cell = Mixture(
            registry, FluidUnits.CELL_VOLUME, Constants.T0,
            mapOf(WetChemistry.WATER to 55.5, WetChemistry.CALCIUM_CARBONATE to 1.0),
        )

        engine.simulate(cell, 1.0, 3_600)

        val dissolved = cell.amount(WetChemistry.CALCIUM_2_ION)
        assertTrue(dissolved > 1e-9) { "dissolution has to be happening at all, but only $dissolved mol moved" }
        assertTrue(dissolved < 1e-4) { "an hour cannot saturate a litre from a lump, but $dissolved mol did" }
        assertTrue(cell.amount(WetChemistry.CALCIUM_CARBONATE) > 0.99) { "the lump should stay a lump" }
        // Both ions come off together, so the water stays neutral.
        assertEquals(cell.amount(WetChemistry.CALCIUM_2_ION), cell.amount(WetChemistry.CARBONATE_ION), 1e-12)
    }
}
