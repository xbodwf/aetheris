package dev.aetheris.engine.chem

import kotlin.math.abs

/**
 * Element and charge bookkeeping.
 *
 * A reaction that does not balance would silently create or destroy matter (or charge), so
 * every reaction is checked when an engine is built. The same helpers are used by tests to
 * prove that a simulation step leaves the element and charge ledgers untouched.
 */
object Conservation {
    /** Per-element imbalance of a reaction: products minus reactants. */
    data class Violation(val element: String, val delta: Double) {
        override fun toString(): String =
            "$element ${if (delta > 0) "+" else ""}${"%.6g".format(delta)}"
    }

    const val DEFAULT_TOLERANCE: Double = 1e-9

    fun balance(reaction: Reaction, registry: SpeciesRegistry): Map<String, Double> {
        val net = HashMap<String, Double>()
        for ((id, coefficient) in reaction.reactants) {
            accumulate(net, registry.require(id).formula, -coefficient)
        }
        for ((id, coefficient) in reaction.products) {
            accumulate(net, registry.require(id).formula, coefficient)
        }
        return net
    }

    fun violations(
        reaction: Reaction,
        registry: SpeciesRegistry,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): List<Violation> =
        balance(reaction, registry)
            .filter { abs(it.value) > tolerance }
            .map { (element, delta) -> Violation(element, delta) }

    fun isBalanced(
        reaction: Reaction,
        registry: SpeciesRegistry,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): Boolean =
        violations(reaction, registry, tolerance).isEmpty() &&
            abs(netCharge(reaction, registry)) <= tolerance

    fun requireBalanced(reaction: Reaction, registry: SpeciesRegistry) {
        val violations = violations(reaction, registry)
        val netCharge = netCharge(reaction, registry)
        if (violations.isNotEmpty() || abs(netCharge) > DEFAULT_TOLERANCE) {
            val problems = violations.map { it.toString() }.toMutableList()
            if (abs(netCharge) > DEFAULT_TOLERANCE) {
                problems += "charge ${if (netCharge > 0) "+" else ""}${"%.6g".format(netCharge)}"
            }
            throw IllegalArgumentException(
                "Reaction '${reaction.id}' is not balanced: ${problems.joinToString(", ")}"
            )
        }
    }

    /**
     * Net charge produced by a reaction: products minus reactants, elementary charge units.
     *
     * Charge is as rigid a conservation law as the elements - a step that changed it would be
     * creating or destroying charged matter - so [requireBalanced] rejects it alongside any
     * element imbalance.
     */
    fun netCharge(reaction: Reaction, registry: SpeciesRegistry): Double =
        chargeOf(reaction.products, registry) - chargeOf(reaction.reactants, registry)

    /** Net charge of a reaction side, in elementary charge units. */
    fun chargeOf(side: Map<String, Double>, registry: SpeciesRegistry): Double =
        side.entries.sumOf { (id, coefficient) -> coefficient * registry.require(id).formula.charge }

    private fun accumulate(target: MutableMap<String, Double>, formula: Formula, multiplier: Double) {
        for ((element, count) in formula.counts) {
            target.merge(element, multiplier * count, Double::plus)
        }
    }
}
