package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * All three states of matter in one cell: the gas headspace above a solution, with a solid
 * precipitate at the bottom. These tests pin down the behavior that a single flat species map
 * could not express — per-phase volumes, molarity, immiscibility and unit solid activity.
 */
class MultiPhaseTest {

    private val registry = SpeciesRegistry.defaults()

    private fun aqueousRegistry(): SpeciesRegistry {
        val r = SpeciesRegistry.defaults()
        // Illustrative aqueous ions (J/mol, J/(mol*K)); cp kept positive, magnitudes are placeholders.
        r.register(Species("test:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 1.0))
        r.register(Species("test:hydroxide", "Hydroxide ion", Formula.parse("OH^-"), Phase.AQUEOUS, -230_015.0, 1.0))
        return r
    }

    @Test
    fun `gas, liquid and solid coexist as distinct phases`() {
        val mixture = Mixture(
            registry, volume = 1.0, temperatureK = 300.0,
            composition = mapOf(
                SpeciesRegistry.H2O_LIQUID to 1000.0,        // liquid solvent
                "aetheris:graphite" to 2.0,                  // solid precipitate
                SpeciesRegistry.N2 to 40.0,                  // gas headspace
            ),
        )

        val kinds = mixture.phaseStates.map { it.kind }.toSet()
        assertEquals(setOf(PhaseKind.GAS, PhaseKind.LIQUID, PhaseKind.SOLID), kinds)

        val liquid = mixture.phaseStates.first { it.kind == PhaseKind.LIQUID }
        assertEquals(1000.0, liquid.amount(SpeciesRegistry.H2O_LIQUID), 1e-9)
        assertFalse(liquid.speciesIds.contains("aetheris:graphite"), "solid must not dissolve into the liquid")

        val solid = mixture.phaseStates.first { it.kind == PhaseKind.SOLID }
        assertEquals(2.0, solid.amount("aetheris:graphite"), 1e-9)
    }

    @Test
    fun `gas sees only the headspace left by condensed matter`() {
        // 50 kmol of liquid water takes ~0.9 m^3 of the 1 m^3 cell (18 cm^3/mol), leaving ~0.1 m^3.
        val mixture = Mixture(
            registry, volume = 1.0, temperatureK = 298.15,
            composition = mapOf(SpeciesRegistry.H2O_LIQUID to 50_000.0, SpeciesRegistry.N2 to 4.09),
        )
        val condensed = 50_000.0 * registry.require(SpeciesRegistry.H2O_LIQUID).molarVolume
        assertEquals(condensed, mixture.condensedVolume(), 1e-9)
        assertEquals(1.0 - condensed, mixture.gasVolume(), 1e-9)
        assertTrue(condensed > 0.85 && condensed < 0.95, "water should fill most of the cell")

        // Same moles of gas in a cell with no liquid would give a much lower pressure.
        val dry = Mixture(registry, 1.0, 298.15, mapOf(SpeciesRegistry.N2 to 4.09))
        assertTrue(
            mixture.partialPressure(SpeciesRegistry.N2) > dry.partialPressure(SpeciesRegistry.N2),
            "gas squeezed into the headspace must be at higher pressure",
        )
    }

    @Test
    fun `solute concentration is per solvent volume, not per cell volume`() {
        val mixture = Mixture(
            aqueousRegistry(), volume = 1.0, temperatureK = 298.15,
            composition = mapOf(SpeciesRegistry.H2O_LIQUID to 1000.0, "test:hydrogen_ion" to 1.0),
        )
        // ~1000 mol water = ~18 L, so 1 mol solute is about 1/18 mol/L.
        assertEquals(1.0 / 18.0, mixture.molarity("test:hydrogen_ion"), 5e-4)
        assertEquals(1.0 / 0.018, mixture.concentration("test:hydrogen_ion"), 0.1)
        // Activity follows the solvent volume (0.018 m^3), not the 1 m^3 cell.
        assertEquals(mixture.concentration("test:hydrogen_ion"), mixture.activity("test:hydrogen_ion"), 1e-9)
        assertFalse(mixture.activity("test:hydrogen_ion") == mixture.amount("test:hydrogen_ion") / mixture.volume)
    }

    @Test
    fun `pure solid activity is one regardless of how much is present`() {
        val some = Mixture(registry, 1.0, 298.15, mapOf("aetheris:graphite" to 1.0))
        val lots = Mixture(registry, 1.0, 298.15, mapOf("aetheris:graphite" to 1000.0))
        assertEquals(1.0, some.activity("aetheris:graphite"), 1e-9)
        assertEquals(1.0, lots.activity("aetheris:graphite"), 1e-9)
        assertEquals(0.0, Mixture(registry, 1.0, 298.15).activity("aetheris:graphite"), 1e-9)
    }

    @Test
    fun `immiscible liquids separate while a shared group mixes`() {
        // Water (group "water") and benzene (no group -> its own phase) do not mix.
        val twoLiquids = Mixture(
            registry, 1.0, 298.15,
            mapOf(
                SpeciesRegistry.H2O_LIQUID to 500.0,
                "aetheris:benzene" to 100.0,
            ),
        )
        assertEquals(2, twoLiquids.phaseStates.count { it.kind == PhaseKind.LIQUID })

        // Two liquids placed in the same miscibility group form a single phase.
        val r = SpeciesRegistry()
        r.register(Species("test:water", "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.3, solventGroup = "water"))
        r.register(Species("test:oilA", "Oil A", Formula.parse("C6H6"), Phase.LIQUID, 49_000.0, 136.1, solventGroup = "oil"))
        r.register(Species("test:oilB", "Oil B", Formula.parse("C7H8"), Phase.LIQUID, 50_000.0, 157.0, solventGroup = "oil"))
        val twoOils = Mixture(r, 1.0, 298.15, mapOf("test:water" to 500.0, "test:oilA" to 50.0, "test:oilB" to 50.0))
        // water + one merged oil phase = 2 liquid phases, not 3.
        assertEquals(2, twoOils.phaseStates.count { it.kind == PhaseKind.LIQUID })
        val oil = twoOils.phaseStates.first { it.kind == PhaseKind.LIQUID && it.group == "oil" }
        assertEquals(100.0, oil.totalMoles(), 1e-9)
    }

    @Test
    fun `an aqueous reaction runs on solvent-volume kinetics and conserves charge`() {
        val r = aqueousRegistry()
        val neutralization = Reaction(
            id = "test:neutralization",
            reactants = mapOf("test:hydrogen_ion" to 1.0, "test:hydroxide" to 1.0),
            products = mapOf(SpeciesRegistry.H2O_LIQUID to 1.0),
            activationEnergy = 10_000.0,
            preExponential = 1.0e3,
        )
        val engine = ReactionEngine(r, listOf(neutralization))
        val mixture = Mixture(
            r, volume = 1.0, temperatureK = 298.15,
            composition = mapOf(
                SpeciesRegistry.H2O_LIQUID to 1000.0,
                "test:hydrogen_ion" to 0.1,
                "test:hydroxide" to 0.1,
            ),
        )
        val elementsBefore = mixture.elementTotals()

        val report = engine.step(mixture, 1.0)

        assertTrue(report.reacted)
        assertEquals(0.0, mixture.amount("test:hydrogen_ion"), 1e-9)
        assertEquals(0.0, mixture.amount("test:hydroxide"), 1e-9)
        assertEquals(1000.1, mixture.amount(SpeciesRegistry.H2O_LIQUID), 1e-9)
        // The reaction acts over the ~18 L of solution, not the 1 m^3 cell.
        assertTrue(mixture.reactionVolume(neutralization) < 0.02)
        assertEquals(0.0, mixture.charge(), 1e-9)
        for ((element, moles) in elementsBefore) {
            assertEquals(moles, mixture.elementTotals().getValue(element), 1e-9, "element $element")
        }
    }

    @Test
    fun `density orders solid above liquid above gas and matches water and air`() {
        val water = registry.require(SpeciesRegistry.H2O_LIQUID)
        val graphite = registry.require("aetheris:graphite")
        assertEquals(1000.0, water.density, 5.0)
        assertTrue(graphite.density > water.density, "graphite (~2500) should be denser than water")

        // 40.9 mol of N2 in 1 m^3 at 298.15 K is ~1 atm, i.e. ~1.15 kg/m^3.
        val air = Mixture(registry, 1.0, 298.15, mapOf(SpeciesRegistry.N2 to 40.9))
        assertEquals(1.15, air.gasDensity(), 0.05)

        // Helium is lighter than air at the same moles and volume: it would rise.
        val helium = Mixture(registry, 1.0, 298.15, mapOf("aetheris:helium" to 40.9))
        assertTrue(helium.gasDensity() < air.gasDensity())
    }

    @Test
    fun `all-gas cells keep the original cell-volume behaviour`() {
        val mixture = Mixture(
            registry, 1.0, 298.15, mapOf(SpeciesRegistry.N2 to 40.9),
        )
        assertEquals(1.0, mixture.gasVolume(), 1e-9)
        assertEquals(0.0, mixture.condensedVolume(), 1e-9)
        // 40.9 mol in 1 m^3 at 298.15 K is about 1 atm.
        assertEquals(101_325.0, mixture.totalPressure(), 500.0)
    }
}
