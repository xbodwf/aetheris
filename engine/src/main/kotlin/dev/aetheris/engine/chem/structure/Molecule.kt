package dev.aetheris.engine.chem.structure

import dev.aetheris.engine.chem.Formula

/**
 * One atom of a [Molecule].
 *
 * [element] is the plain element symbol (`"C"`, `"Cl"`, `"Fe"`); aromaticity is a separate flag
 * rather than a lowercase symbol, so the same element behaves identically whichever way it was
 * written in the source notation.
 *
 * [declaredHydrogens] is the hydrogen count written explicitly for a bracket atom (`[nH]`,
 * `[CH3]`, `[H]`). For an atom written without brackets it is `null`, meaning "derive the count
 * from the standard valence" - see [Molecule.hydrogens].
 */
data class Atom(
    val element: String,
    val charge: Int = 0,
    val declaredHydrogens: Int? = null,
    val aromatic: Boolean = false,
)

/**
 * A bond between two atom indices of a [Molecule].
 *
 * [order] is a real number rather than an enum so an aromatic bond can be the usual 1.5 without a
 * special case anywhere that sums bond orders. [aromatic] records the fact alongside the number,
 * because "is this bond part of an aromatic ring" is a structural question that 1.5 alone does
 * not answer.
 */
data class Bond(
    val first: Int,
    val second: Int,
    val order: Double,
    val aromatic: Boolean = false,
) {
    init {
        require(first != second) { "A bond must join two different atoms (was $first-$second)" }
        require(order > 0.0) { "Bond order must be positive (was $order)" }
    }

    /** The other end of this bond seen from [index]. */
    fun otherEnd(index: Int): Int = when (index) {
        first -> second
        second -> first
        else -> throw IllegalArgumentException("Atom $index is not part of bond $first-$second")
    }

    /** True when [index] is one of this bond's two atoms. */
    fun connects(index: Int): Boolean = index == first || index == second
}

/**
 * Standard valences used to derive implicit hydrogen counts.
 *
 * Only the organic subset and its immediate neighbours are tabulated. An element outside the
 * table reports `null`, which means "no implicit hydrogens": such an atom must be written in
 * brackets with its hydrogen count spelled out, exactly as in standard notation. Metals and
 * other inorganics normally carry none anyway.
 */
object StandardValence {
    private val table = mapOf(
        "H" to 1,
        "B" to 3,
        "C" to 4,
        "N" to 3,
        "O" to 2,
        "F" to 1,
        "Si" to 4,
        "P" to 3,
        "S" to 2,
        "Cl" to 1,
        "Br" to 1,
        "I" to 1,
    )

    /** The standard valence of [element], or `null` when implicit hydrogens do not apply. */
    fun of(element: String): Int? = table[element]
}

/**
 * A molecular graph: atoms plus the bonds between them.
 *
 * This is the layer the engine was missing. A [Formula] can say `C2H6O` but not whether that is
 * ethanol or dimethyl ether, so it cannot recognise a hydroxyl group or tell an ester from a
 * ketone. A [Molecule] can, and that is what lets organic reaction templates be written as
 * "carboxylic acid plus alcohol" instead of as a list of named compounds.
 *
 * Instances are immutable. Build one with [Smiles.parse].
 */
class Molecule(
    val atoms: List<Atom>,
    val bonds: List<Bond>,
) {
    init {
        require(atoms.isNotEmpty()) { "A molecule must contain at least one atom" }
        for (bond in bonds) {
            require(bond.first in atoms.indices && bond.second in atoms.indices) {
                "Bond ${bond.first}-${bond.second} refers to an atom outside 0..${atoms.lastIndex}"
            }
        }
    }

    /** Bond indices incident on each atom, which is what every traversal below walks. */
    private val incident: List<List<Int>> = buildIncident()

    private val groupCounts: Map<FunctionalGroup, Int> by lazy { FunctionalGroups.analyse(this) }

    private fun buildIncident(): List<List<Int>> {
        val lists = List(atoms.size) { ArrayList<Int>() }
        bonds.forEachIndexed { index, bond ->
            lists[bond.first].add(index)
            lists[bond.second].add(index)
        }
        return lists.map { it.toList() }
    }

    val atomCount: Int get() = atoms.size

    /** Bonds incident on [index]. */
    fun bondsOf(index: Int): List<Bond> = incident[index].map { bonds[it] }

    /** Atom indices directly bonded to [index]. */
    fun neighbours(index: Int): List<Int> = incident[index].map { bonds[it].otherEnd(index) }

    /** Atom indices directly bonded to [index], excluding explicit hydrogen atoms. */
    fun heavyNeighbours(index: Int): List<Int> = neighbours(index).filter { atoms[it].element != "H" }

    /** The bond joining [first] and [second], or `null` when they are not bonded. */
    fun bondBetween(first: Int, second: Int): Bond? =
        incident[first].map { bonds[it] }.firstOrNull { it.connects(second) }

    /** Sum of the orders of every bond incident on [index]. */
    fun bondOrderSum(index: Int): Double = incident[index].sumOf { bonds[it].order }

    /**
     * Hydrogen count on [index].
     *
     * A bracket atom reports exactly what was written. Any other atom is filled up to its standard
     * valence: `hydrogen = valence - sum(bond orders)`, floored at zero. Aromatic bonds count 1.5,
     * so benzene's `c` has `4 - 2 * 1.5 = 1` hydrogen and naphthalene's fusion carbons have
     * `4 - 3 * 1.5 < 0`, i.e. none - which is correct for both.
     */
    fun hydrogens(index: Int): Int {
        val atom = atoms[index]
        atom.declaredHydrogens?.let { return it }
        val valence = StandardValence.of(atom.element) ?: return 0
        val remaining = valence - bondOrderSum(index)
        return if (remaining <= 0.0) 0 else Math.round(remaining).toInt()
    }

    /** The formula this graph implies, hydrogens included. */
    fun formula(): Formula {
        val counts = LinkedHashMap<String, Int>()
        for ((index, atom) in atoms.withIndex()) {
            counts[atom.element] = (counts[atom.element] ?: 0) + 1
            val h = hydrogens(index)
            if (h > 0) counts["H"] = (counts["H"] ?: 0) + h
        }
        return Formula.of(counts, atoms.sumOf { it.charge })
    }

    /** Functional groups present anywhere in this molecule. */
    fun functionalGroups(): Set<FunctionalGroup> = groupCounts.keys

    /** How many separate occurrences of [group] this molecule contains. */
    fun functionalGroupCount(group: FunctionalGroup): Int = groupCounts[group] ?: 0

    /** True when [group] occurs at least once. */
    fun has(group: FunctionalGroup): Boolean = functionalGroupCount(group) > 0

    /**
     * True when this molecule contains a ring of exactly [size] atoms that are all aromatic.
     *
     * Used to tell an arene from a mere chain of doubled bonds. The search is a bounded depth-first
     * walk that avoids revisiting atoms, so it terminates on any molecule small enough to be a
     * species in this mod.
     */
    fun hasAromaticRing(size: Int): Boolean {
        for (start in atoms.indices) {
            if (!atoms[start].aromatic) continue
            if (walkRing(start, start, size - 1, mutableSetOf(start))) return true
        }
        return false
    }

    private fun walkRing(start: Int, current: Int, remaining: Int, visited: MutableSet<Int>): Boolean {
        if (remaining == 0) return bondBetween(current, start) != null
        for (next in neighbours(current)) {
            if (next == start) continue
            if (!atoms[next].aromatic) continue
            if (!visited.add(next)) continue
            val found = walkRing(start, next, remaining - 1, visited)
            visited.remove(next)
            if (found) return true
        }
        return false
    }

    override fun toString(): String = "Molecule(${formula()}, ${bonds.size} bonds)"
}
