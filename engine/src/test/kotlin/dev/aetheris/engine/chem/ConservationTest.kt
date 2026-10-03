package dev.aetheris.engine.chem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConservationTest {

    private val registry = SpeciesRegistry.defaults()

    /** CH4 + 2 O2 -> CO2 + 2 H2O(g), the textbook complete-combustion reaction. */
    private val methaneCombustion = Reaction(
        id = "test:methane_combustion",
        reactants = mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0),
        products = mapOf(SpeciesRegistry.CO2 to 1.0, SpeciesRegistry.H2O_GAS to 2.0),
        activationEnergy = 125_000.0,
        preExponential = 1.0e6,
    )

    @Test
    fun `balanced reaction reports no violations`() {
        assertTrue(Conservation.isBalanced(methaneCombustion, registry))
        assertTrue(Conservation.violations(methaneCombustion, registry).isEmpty())
    }

    @Test
    fun `net element balance is zero for every element`() {
        val net = Conservation.balance(methaneCombustion, registry)
        assertEquals(setOf("C", "H", "O"), net.keys)
        for ((element, delta) in net) {
            assertEquals(0.0, delta, 1e-9, "element $element")
        }
    }

    @Test
    fun `hess law enthalpy of methane combustion matches the literature value`() {
        // -393.51 (CO2) + 2 * -241.82 (H2O gas) - -74.81 (CH4) = -802.34 kJ/mol.
        assertEquals(-802_340.0, methaneCombustion.standardEnthalpy(registry), 2_000.0)
    }

    @Test
    fun `an unbalanced reaction is reported with the offending element`() {
        val missingHydrogen = Reaction(
            id = "test:broken",
            reactants = mapOf(SpeciesRegistry.CH4 to 1.0),
            products = mapOf(SpeciesRegistry.CO2 to 1.0),
            activationEnergy = 100_000.0,
            preExponential = 1.0e6,
        )
        assertFalse(Conservation.isBalanced(missingHydrogen, registry))

        val violations = Conservation.violations(missingHydrogen, registry)
        val byElement = violations.associateBy { it.element }
        // Carbon balances, but hydrogen lost 4 and oxygen gained 2.
        assertFalse(byElement.containsKey("C"))
        assertEquals(-4.0, byElement.getValue("H").delta, 1e-9)
        assertEquals(2.0, byElement.getValue("O").delta, 1e-9)

        assertThrows(IllegalArgumentException::class.java) {
            Conservation.requireBalanced(missingHydrogen, registry)
        }
    }

    @Test
    fun `chargeOf sums signed charges on one side`() {
        val acidic = mapOf("aetheris:sulfate" to 1.0)
        // Register a sulfate species just for this check.
        val r = SpeciesRegistry()
        r.register(Species("aetheris:sulfate", "Sulfate", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0))
        assertEquals(-2.0, Conservation.chargeOf(acidic, r), 1e-9)

        val neutral = mapOf(SpeciesRegistry.H2O_GAS to 2.0)
        assertEquals(0.0, Conservation.chargeOf(neutral, registry), 1e-9)
    }

    @Test
    fun `reaction engine refuses to build around an unbalanced reaction`() {
        val bad = Reaction(
            id = "test:broken",
            reactants = mapOf(SpeciesRegistry.CH4 to 1.0),
            products = mapOf(SpeciesRegistry.CO2 to 1.0),
            activationEnergy = 100_000.0,
            preExponential = 1.0e6,
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReactionEngine(registry, listOf(bad))
        }
        assertTrue(error.message!!.contains("not balanced"))
    }

    @Test
    fun `reaction engine rejects duplicate reaction ids`() {
        val r = Reaction(
            id = "test:dup",
            reactants = mapOf(SpeciesRegistry.CH4 to 1.0, SpeciesRegistry.O2 to 2.0),
            products = mapOf(SpeciesRegistry.CO2 to 1.0, SpeciesRegistry.H2O_GAS to 2.0),
            activationEnergy = 125_000.0,
            preExponential = 1.0e6,
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReactionEngine(registry, listOf(r, r))
        }
        assertTrue(error.message!!.contains("Duplicate reaction id"))
    }

    @Test
    fun `charge is conserved as strictly as the elements`() {
        val r = SpeciesRegistry()
        r.register(Species("aetheris:sulfate", "Sulfate", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0))
        r.register(Species("aetheris:sulfur", "Sulfur", Formula.parse("S"), Phase.SOLID, 0.0, 22.6))
        r.register(Species(SpeciesRegistry.O2, "Oxygen", Formula.parse("O2"), Phase.GAS, 0.0, 29.38))

        // Elements balance (S and O), but the net charge leaks from -2 to 0.
        val leak = Reaction(
            id = "test:charge_leak",
            reactants = mapOf("aetheris:sulfate" to 1.0),
            products = mapOf("aetheris:sulfur" to 1.0, SpeciesRegistry.O2 to 2.0),
            activationEnergy = 100_000.0,
            preExponential = 1.0e6,
        )
        assertEquals(2.0, Conservation.netCharge(leak, r), 1e-9)
        assertFalse(Conservation.isBalanced(leak, r))
        assertThrows(IllegalArgumentException::class.java) { Conservation.requireBalanced(leak, r) }
        assertThrows(IllegalArgumentException::class.java) { ReactionEngine(r, listOf(leak)) }
    }

    @Test
    fun `a charge-balanced ionic reaction is accepted`() {
        val r = SpeciesRegistry()
        r.register(Species("aetheris:sulfate", "Sulfate", Formula.parse("SO4^2-"), Phase.AQUEOUS, -909_270.0, 25.0))
        r.register(Species("aetheris:hydrogen_ion", "Hydrogen ion", Formula.parse("H^+"), Phase.AQUEOUS, 0.0, 0.1))
        r.register(Species("aetheris:sulfuric_acid", "Sulfuric acid", Formula.parse("H2SO4"), Phase.AQUEOUS, -909_270.0, 100.0))

        // 2 H+ + SO4^2- -> H2SO4: elements and charge both balance.
        val acid = Reaction(
            id = "test:sulfuric_acid",
            reactants = mapOf("aetheris:hydrogen_ion" to 2.0, "aetheris:sulfate" to 1.0),
            products = mapOf("aetheris:sulfuric_acid" to 1.0),
            activationEnergy = 10_000.0,
            preExponential = 1.0e3,
        )
        assertEquals(0.0, Conservation.netCharge(acid, r), 1e-9)
        assertTrue(Conservation.isBalanced(acid, r))
        Conservation.requireBalanced(acid, r)
    }
}
