package dev.aetheris.engine.chem.structure

import dev.aetheris.engine.chem.Formula
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmilesTest {

    @Test
    fun `implicit hydrogens fill atoms up to their standard valence`() {
        assertEquals(Formula.parse("CH4"), Smiles.parse("C").formula())
        assertEquals(Formula.parse("H2O"), Smiles.parse("O").formula())
        assertEquals(Formula.parse("NH3"), Smiles.parse("N").formula())
        assertEquals(Formula.parse("C2H6O"), Smiles.parse("CCO").formula())
    }

    @Test
    fun `branches hang off the preceding atom`() {
        assertEquals(Formula.parse("C4H10"), Smiles.parse("CC(C)C").formula())
        assertEquals(Formula.parse("C2H4O2"), Smiles.parse("CC(=O)O").formula())
        assertEquals(Formula.parse("CH4O"), Smiles.parse("CO").formula())
    }

    @Test
    fun `ring closures build a cycle`() {
        assertEquals(Formula.parse("C6H12"), Smiles.parse("C1CCCCC1").formula())
        assertEquals(Formula.parse("C6H6"), Smiles.parse("c1ccccc1").formula())
    }

    @Test
    fun `aromatic bonds count as one and a half`() {
        val benzene = Smiles.parse("c1ccccc1")
        assertEquals(6, benzene.atomCount)
        assertTrue(benzene.bonds.all { it.aromatic })
        assertEquals(1.5, benzene.bonds.first().order, 1e-9)
        assertEquals(1, benzene.hydrogens(0))
    }

    @Test
    fun `fused aromatic carbons carry no hydrogen`() {
        val naphthalene = Smiles.parse("c1ccc2ccccc2c1")
        assertEquals(Formula.parse("C10H8"), naphthalene.formula())
        assertEquals(0, naphthalene.hydrogens(3))
        assertEquals(0, naphthalene.hydrogens(8))
        assertEquals(1, naphthalene.hydrogens(0))
    }

    @Test
    fun `bracket atoms keep their declared hydrogens and charge`() {
        assertEquals(Formula.parse("C4H5N"), Smiles.parse("c1cc[nH]c1").formula())

        val copper = Smiles.parse("[Cu+2]")
        assertEquals(2, copper.atoms[0].charge)
        assertEquals(Formula.parse("Cu^2+"), copper.formula())

        assertEquals(Formula.parse("OH^-"), Smiles.parse("[OH-]").formula())
    }

    @Test
    fun `components separated by a dot share one molecule`() {
        val salt = Smiles.parse("[Na+].[Cl-]")
        assertEquals(2, salt.atomCount)
        assertEquals(0, salt.bonds.size)
        assertEquals(Formula.parse("NaCl"), salt.formula())

        assertEquals(Formula.parse("SO4^2-"), Smiles.parse("[O-]S(=O)(=O)[O-]").formula())
    }

    @Test
    fun `multiple bonds keep their order`() {
        val ethene = Smiles.parse("C=C")
        assertEquals(Formula.parse("C2H4"), ethene.formula())
        assertEquals(2.0, ethene.bonds[0].order, 1e-9)

        val ethyne = Smiles.parse("C#C")
        assertEquals(Formula.parse("C2H2"), ethyne.formula())
        assertEquals(3.0, ethyne.bonds[0].order, 1e-9)
    }

    @Test
    fun `hydrate-style separators are not confused with components`() {
        // A dot between aromatic atoms still means "separate component", so a lone benzene ring
        // must be written closed. This guards the parser against reading '.' as a bond.
        val mixture = Smiles.parse("C.C")
        assertEquals(2, mixture.atomCount)
        assertEquals(0, mixture.bonds.size)
    }

    @Test
    fun `rejects malformed structures`() {
        assertThrows(SmilesException::class.java) { Smiles.parse("") }
        assertThrows(SmilesException::class.java) { Smiles.parse("   ") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C1CC") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C(C") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C)") }
        assertThrows(SmilesException::class.java) { Smiles.parse("Xx") }
        assertThrows(SmilesException::class.java) { Smiles.parse("[Qq]") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C*") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C=") }
        assertThrows(SmilesException::class.java) { Smiles.parse("C==C") }
    }

    @Test
    fun `a molecule without structure knowledge reports none`() {
        val atom = Atom("Fe")
        val graph = Molecule(listOf(atom), emptyList())
        assertEquals(Formula.parse("Fe"), graph.formula())
        assertTrue(graph.functionalGroups().isEmpty())
        assertFalse(graph.hasAromaticRing(6))
    }
}
