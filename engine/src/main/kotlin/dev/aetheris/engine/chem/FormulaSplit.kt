package dev.aetheris.engine.chem

/**
 * Reads a formula the way a chemist writes it into the ions that actually make it up.
 *
 * This exists because the catalogue cannot hold most of what a player will type. `HCl` is not a species here
 * and never will be: in solution it is H+ and Cl-, and that is how every reaction refers to it. The same is
 * true of `CuSO4`, `NaOH`, `H2SO4` - the formula names a substance, the model holds ions. So a formula is
 * *decomposed*: find the registered ions whose atoms and charges add up to it, and contribute each of them at
 * its own coefficient.
 *
 * The search is small because it is pruned twice. Only species in the requested phase are candidates, and a
 * candidate whose elements are not a subset of the target's cannot appear at any coefficient, so it is dropped
 * before the search starts - which is what narrows `HCl` down to the two ions that contain nothing but hydrogen
 * and chlorine. During the search, partial sums are abandoned as soon as an element exceeds what the target
 * holds, and because every coefficient is positive that is monotone in the coefficient, so an overshoot ends
 * that candidate's branch rather than merely failing it.
 *
 * Coefficients are bounded by [MAX_COEFFICIENT]. Nothing in the catalogue needs more, and an unbounded search
 * over an unbounded set is a way to hang a command.
 */
object FormulaSplit {

    /** Largest coefficient any one ion may take in a decomposition. */
    const val MAX_COEFFICIENT = 4

    /** The species in [phase] that a formula decomposes into, or `null` when nothing fits. */
    fun decompose(
        formula: Formula,
        registry: SpeciesRegistry,
        phase: Phase = Phase.AQUEOUS,
    ): Map<String, Double>? {
        val candidates = registry.all
            .filter { it.phase == phase && fitsInside(it.formula, formula) }
            .sortedBy { it.id }
        if (candidates.isEmpty()) return null

        val elements = HashMap<String, Int>()
        val chosen = LinkedHashMap<String, Double>()
        return if (search(formula, candidates, 0, elements, chosen)) chosen else null
    }

    /** True when every element of [part] appears in [whole] at least as often. */
    private fun fitsInside(part: Formula, whole: Formula): Boolean =
        part.counts.all { (element, count) -> count <= (whole.counts[element] ?: 0) }

    private fun search(
        target: Formula,
        candidates: List<Species>,
        index: Int,
        elements: MutableMap<String, Int>,
        chosen: MutableMap<String, Double>,
    ): Boolean {
        if (index == candidates.size) return matches(target, candidates, chosen)

        val species = candidates[index]
        for (coefficient in 0..MAX_COEFFICIENT) {
            if (coefficient > 0) {
                for ((element, count) in species.formula.counts) {
                    elements[element] = (elements[element] ?: 0) + count
                }
                chosen[species.id] = coefficient.toDouble()
            }
            // Monotone in the coefficient: once one element is over budget, every larger coefficient is too.
            if (overshoots(elements, target)) {
                if (coefficient > 0) undo(species, elements, chosen)
                return false
            }
            if (search(target, candidates, index + 1, elements, chosen)) return true
            if (coefficient > 0) undo(species, elements, chosen)
        }
        return false
    }

    /**
     * Judges a complete assignment by **recomputing** it from the assignment itself.
     *
     * The running totals the search prunes with are not consulted here, and that is deliberate: an earlier
     * version compared them directly, so a map still carrying zero-count leftovers from undone branches
     * compared unequal to a target that simply did not mention those elements, and a charge threaded through
     * the loop was never restored between iterations. Both are the same mistake - trusting incremental state
     * at the moment of decision - and both produced wrong answers in opposite directions. Recomputing from
     * the assignment cannot.
     */
    private fun matches(target: Formula, candidates: List<Species>, chosen: Map<String, Double>): Boolean {
        if (chosen.isEmpty()) return false
        val byId = candidates.associateBy { it.id }
        val elements = HashMap<String, Int>()
        var charge = 0.0
        for ((id, coefficient) in chosen) {
            val species = byId.getValue(id)
            charge += species.formula.charge * coefficient
            for ((element, count) in species.formula.counts) {
                elements[element] = (elements[element] ?: 0) + count * coefficient.toInt()
            }
        }
        return charge == target.charge.toDouble() && elements == target.counts
    }

    private fun undo(species: Species, elements: MutableMap<String, Int>, chosen: MutableMap<String, Double>) {
        for ((element, count) in species.formula.counts) {
            elements[element] = (elements[element] ?: 0) - count
        }
        chosen.remove(species.id)
    }

    private fun overshoots(elements: Map<String, Int>, target: Formula): Boolean =
        elements.any { (element, count) -> count > (target.counts[element] ?: 0) }
}
