package dev.aetheris.engine.chem.structure

import dev.aetheris.engine.chem.Formula
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FunctionalGroupTest {

    @Test
    fun `the same formula can give different groups`() {
        val ethanol = Smiles.parse("CCO")
        val dimethylEther = Smiles.parse("COC")

        // The whole reason a structure layer exists: the formula cannot tell these apart.
        assertEquals(ethanol.formula(), dimethylEther.formula())

        assertTrue(ethanol.has(FunctionalGroup.ALCOHOL))
        assertFalse(ethanol.has(FunctionalGroup.ETHER))
        assertTrue(dimethylEther.has(FunctionalGroup.ETHER))
        assertFalse(dimethylEther.has(FunctionalGroup.ALCOHOL))
    }

    @Test
    fun `the carbonyl family is told apart`() {
        assertTrue(Smiles.parse("CC=O").has(FunctionalGroup.ALDEHYDE))
        assertTrue(Smiles.parse("CC(C)=O").has(FunctionalGroup.KETONE))
        assertTrue(Smiles.parse("CC(=O)O").has(FunctionalGroup.CARBOXYLIC_ACID))
        assertTrue(Smiles.parse("CCOC(C)=O").has(FunctionalGroup.ESTER))
        assertTrue(Smiles.parse("CC(=O)N").has(FunctionalGroup.AMIDE))
    }

    @Test
    fun `an ester is not also an ether, an alcohol or an acid`() {
        val ester = Smiles.parse("CCOC(C)=O")
        assertEquals(Formula.parse("C4H8O2"), ester.formula())
        assertEquals(1, ester.functionalGroupCount(FunctionalGroup.ESTER))
        assertFalse(ester.has(FunctionalGroup.ETHER))
        assertFalse(ester.has(FunctionalGroup.ALCOHOL))
        assertFalse(ester.has(FunctionalGroup.KETONE))
        assertFalse(ester.has(FunctionalGroup.CARBOXYLIC_ACID))
    }

    @Test
    fun `a diester counts both ester bonds`() {
        val oxalate = Smiles.parse("COC(=O)C(=O)OC")
        assertEquals(Formula.parse("C4H6O4"), oxalate.formula())
        assertEquals(2, oxalate.functionalGroupCount(FunctionalGroup.ESTER))
    }

    @Test
    fun `an arene is recognised and phenol is not an alcohol`() {
        assertTrue(Smiles.parse("c1ccccc1").has(FunctionalGroup.ARENE))
        assertFalse(Smiles.parse("C1CCCCC1").has(FunctionalGroup.ARENE))

        val phenol = Smiles.parse("Oc1ccccc1")
        assertTrue(phenol.has(FunctionalGroup.PHENOL))
        assertFalse(phenol.has(FunctionalGroup.ALCOHOL))
        assertTrue(phenol.has(FunctionalGroup.ARENE))
    }

    @Test
    fun `a ring nitrogen is not an amine but aniline is`() {
        assertFalse(Smiles.parse("c1ccncc1").has(FunctionalGroup.PRIMARY_AMINE))
        assertTrue(Smiles.parse("Nc1ccccc1").has(FunctionalGroup.PRIMARY_AMINE))
    }

    @Test
    fun `amines are graded by how many hydrogens remain`() {
        assertTrue(Smiles.parse("CCN").has(FunctionalGroup.PRIMARY_AMINE))
        assertTrue(Smiles.parse("CCNC").has(FunctionalGroup.SECONDARY_AMINE))
        assertTrue(Smiles.parse("CCN(C)C").has(FunctionalGroup.TERTIARY_AMINE))
        assertFalse(Smiles.parse("CCN(C)C").has(FunctionalGroup.PRIMARY_AMINE))
    }

    @Test
    fun `an amino acid is both at once`() {
        val glycine = Smiles.parse("NCC(=O)O")
        assertEquals(Formula.parse("C2H5NO2"), glycine.formula())
        assertTrue(glycine.has(FunctionalGroup.PRIMARY_AMINE))
        assertTrue(glycine.has(FunctionalGroup.CARBOXYLIC_ACID))
    }

    @Test
    fun `nitro reads the same in either spelling`() {
        assertTrue(Smiles.parse("C[N+](=O)[O-]").has(FunctionalGroup.NITRO))
        assertTrue(Smiles.parse("CN(=O)=O").has(FunctionalGroup.NITRO))
        assertFalse(Smiles.parse("CN").has(FunctionalGroup.NITRO))
    }

    @Test
    fun `multiple bonds are reported`() {
        assertTrue(Smiles.parse("C=C").has(FunctionalGroup.ALKENE))
        assertTrue(Smiles.parse("C#C").has(FunctionalGroup.ALKYNE))
        assertTrue(Smiles.parse("CC#N").has(FunctionalGroup.NITRILE))
        assertFalse(Smiles.parse("CC").has(FunctionalGroup.ALKENE))
    }

    @Test
    fun `aromatic bonds are not alkenes`() {
        val benzene = Smiles.parse("c1ccccc1")
        assertFalse(benzene.has(FunctionalGroup.ALKENE))
        assertFalse(benzene.has(FunctionalGroup.ALKYNE))
    }

    @Test
    fun `thiol and ether are recognised`() {
        assertTrue(Smiles.parse("CCS").has(FunctionalGroup.THIOL))
        assertTrue(Smiles.parse("CCOCC").has(FunctionalGroup.ETHER))
        assertFalse(Smiles.parse("CCOCC").has(FunctionalGroup.THIOL))
    }

    @Test
    fun `an aldehyde is not counted as a ketone`() {
        val acetaldehyde = Smiles.parse("CC=O")
        assertEquals(1, acetaldehyde.functionalGroupCount(FunctionalGroup.ALDEHYDE))
        assertFalse(acetaldehyde.has(FunctionalGroup.KETONE))

        val acetone = Smiles.parse("CC(C)=O")
        assertEquals(1, acetone.functionalGroupCount(FunctionalGroup.KETONE))
        assertFalse(acetone.has(FunctionalGroup.ALDEHYDE))
    }
}
