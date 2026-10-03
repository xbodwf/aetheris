package dev.aetheris.engine.chem.structure

/**
 * A functional group the engine can recognise from structure alone.
 *
 * This is the vocabulary organic reaction templates are written in. "Carboxylic acid plus alcohol
 * gives ester plus water" is expressible with these constants; "acetic acid plus ethanol gives
 * ethyl acetate" would need every compound named in advance. The first scales, the second does not.
 *
 * [label] is the human-readable name used in tooltips and diagnostics.
 */
enum class FunctionalGroup(val label: String) {
    CARBOXYLIC_ACID("carboxylic acid"),
    ESTER("ester"),
    AMIDE("amide"),
    ALDEHYDE("aldehyde"),
    KETONE("ketone"),
    ALCOHOL("alcohol"),
    PHENOL("phenol"),
    ETHER("ether"),
    PRIMARY_AMINE("primary amine"),
    SECONDARY_AMINE("secondary amine"),
    TERTIARY_AMINE("tertiary amine"),
    NITRILE("nitrile"),
    ALKENE("alkene"),
    ALKYNE("alkyne"),
    ARENE("arene"),
    THIOL("thiol"),
    NITRO("nitro"),
    ;

    /** True for groups built around a `C=O` carbon, which share the carbonyl chemistry. */
    val isCarbonyl: Boolean
        get() = this == CARBOXYLIC_ACID || this == ESTER || this == AMIDE ||
            this == ALDEHYDE || this == KETONE
}

/**
 * Recognises [FunctionalGroup]s in a [Molecule].
 *
 * Detection is site-based, not substructure-matching: every carbonyl carbon, oxygen, nitrogen and
 * multiple bond is visited once and classified, and sites already claimed by a group are consumed
 * so a carboxyl oxygen is not also reported as an alcohol. Counts are therefore the number of
 * independent occurrences, which is what lets a template say "this diester has two ester bonds".
 *
 * Known approximations, stated rather than hidden: an acid anhydride reads as two esters, because
 * both of its carbonyl carbons carry one single-bonded oxygen; an aromatic ring nitrogen is left
 * unclassified rather than guessed as an amine, since it is not basic in the amine sense. Both are
 * refinements for when a reaction actually needs to tell them apart.
 */
object FunctionalGroups {

    /** Group to number of independent occurrences in [molecule]. */
    fun analyse(molecule: Molecule): Map<FunctionalGroup, Int> {
        val counts = LinkedHashMap<FunctionalGroup, Int>()
        val consumed = HashSet<Int>()

        fun add(group: FunctionalGroup) {
            counts[group] = (counts[group] ?: 0) + 1
        }

        // Carbonyl family: every carbon carrying a double bond to oxygen is exactly one of these.
        for (index in molecule.atoms.indices) {
            if (molecule.atoms[index].element != "C") continue
            val doubleOxygen = molecule.neighbours(index).firstOrNull {
                molecule.atoms[it].element == "O" && orderOf(molecule, index, it) >= 1.9
            } ?: continue

            val singleBonds = molecule.neighbours(index).filter { orderOf(molecule, index, it) < 1.9 }
            val singleOxygens = singleBonds.filter { molecule.atoms[it].element == "O" }
            val acidOxygen = singleOxygens.firstOrNull { molecule.hydrogens(it) > 0 }
            val esterOxygen = singleOxygens.firstOrNull { molecule.hydrogens(it) == 0 }
            val singleNitrogen = singleBonds.firstOrNull { molecule.atoms[it].element == "N" }

            when {
                // Order matters: a carboxyl is also an ester-like and aldehyde-like arrangement.
                acidOxygen != null -> {
                    add(FunctionalGroup.CARBOXYLIC_ACID)
                    consumed += acidOxygen
                }

                esterOxygen != null -> {
                    add(FunctionalGroup.ESTER)
                    consumed += esterOxygen
                }

                singleNitrogen != null -> {
                    add(FunctionalGroup.AMIDE)
                    consumed += singleNitrogen
                }

                molecule.hydrogens(index) > 0 -> add(FunctionalGroup.ALDEHYDE)

                else -> add(FunctionalGroup.KETONE)
            }
            consumed += doubleOxygen
        }

        // Triple bonds: a C#N is a nitrile, a C#C an alkyne, a C=C an alkene.
        for (bond in molecule.bonds) {
            if (bond.aromatic) continue
            val first = molecule.atoms[bond.first].element
            val second = molecule.atoms[bond.second].element
            if (bond.order >= 2.9) {
                if (first == "C" && second == "N") {
                    add(FunctionalGroup.NITRILE)
                    consumed += bond.second
                } else if (first == "N" && second == "C") {
                    add(FunctionalGroup.NITRILE)
                    consumed += bond.first
                } else if (first == "C" && second == "C") {
                    add(FunctionalGroup.ALKYNE)
                }
            } else if (bond.order >= 1.9 && first == "C" && second == "C") {
                add(FunctionalGroup.ALKENE)
            }
        }

        // Single-bonded oxygen left over: an alcohol, a phenol, or an ether.
        for (index in molecule.atoms.indices) {
            if (molecule.atoms[index].element != "O" || index in consumed) continue
            val heavy = molecule.heavyNeighbours(index)
            if (molecule.hydrogens(index) > 0 && heavy.size == 1) {
                val anchor = heavy.first()
                add(if (molecule.atoms[anchor].aromatic) FunctionalGroup.PHENOL else FunctionalGroup.ALCOHOL)
            } else if (molecule.hydrogens(index) == 0 && heavy.size == 2) {
                add(FunctionalGroup.ETHER)
            }
        }

        // Nitrogen: nitro first, then the amines by how many hydrogens remain on the nitrogen.
        for (index in molecule.atoms.indices) {
            if (molecule.atoms[index].element != "N" || index in consumed) continue
            if (molecule.atoms[index].aromatic) continue
            val heavy = molecule.heavyNeighbours(index)
            // Nitro is counted by bond order, not by "two double bonds": the charge-separated
            // spelling [N+](=O)[O-] is the same group as N(=O)=O and must read identically.
            val oxygenOrder = heavy
                .filter { molecule.atoms[it].element == "O" }
                .sumOf { orderOf(molecule, index, it) }
            val oxygens = heavy.count { molecule.atoms[it].element == "O" }
            if (oxygens >= 2 && oxygenOrder >= 3.0) {
                add(FunctionalGroup.NITRO)
                continue
            }
            if (heavy.isEmpty() || heavy.any { molecule.atoms[it].element != "C" }) continue
            when (molecule.hydrogens(index)) {
                2 -> add(FunctionalGroup.PRIMARY_AMINE)
                1 -> add(FunctionalGroup.SECONDARY_AMINE)
                0 -> add(FunctionalGroup.TERTIARY_AMINE)
            }
        }

        // Sulfur carrying a hydrogen is a thiol.
        for (index in molecule.atoms.indices) {
            if (molecule.atoms[index].element == "S" && molecule.hydrogens(index) > 0) {
                add(FunctionalGroup.THIOL)
            }
        }

        if (molecule.hasAromaticRing(6)) add(FunctionalGroup.ARENE)

        return counts
    }

    /** How many independent occurrences of [group] [molecule] contains. */
    fun count(molecule: Molecule, group: FunctionalGroup): Int = analyse(molecule)[group] ?: 0

    private fun orderOf(molecule: Molecule, from: Int, to: Int): Double =
        molecule.bondBetween(from, to)?.order ?: 0.0
}
