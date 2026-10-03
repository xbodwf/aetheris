package dev.aetheris.engine.chem

/**
 * Physical kind of a phase inside a [Mixture].
 *
 * Aqueous and non-aqueous liquids are both [LIQUID]; they are told apart by their
 * [PhaseState.group] (the miscibility group), not by a distinct kind.
 */
enum class PhaseKind { GAS, LIQUID, SOLID }

/**
 * One coexisting, well-mixed phase inside a [Mixture]: a multi-component volume of matter.
 *
 * Splitting a cell's matter into phases (rather than keeping one flat species map) is what makes
 * the three states of matter behave differently:
 *
 *  - the single [PhaseKind.GAS] phase shares the cell's free volume, so its species obey the
 *    ideal-gas law;
 *  - [PhaseKind.LIQUID] phases are keyed by miscibility [group], so two immiscible liquids form
 *    two phases and do not dilute each other, while solutes dissolve into their solvent's group;
 *  - each [PhaseKind.SOLID] precipitate is its own pure phase, so its thermodynamic activity is
 *    1 regardless of how much is present.
 */
class PhaseState(val kind: PhaseKind, val group: String) {

    private val amounts = LinkedHashMap<String, Double>()

    val speciesIds: Set<String> get() = amounts.keys

    val isEmpty: Boolean get() = amounts.isEmpty()

    fun amount(id: String): Double = amounts[id] ?: 0.0

    fun set(id: String, moles: Double) {
        require(moles >= 0.0) { "Amount of '$id' cannot be negative (was $moles)" }
        if (moles == 0.0) amounts.remove(id) else amounts[id] = moles
    }

    fun add(id: String, delta: Double) = set(id, amount(id) + delta)

    fun totalMoles(): Double = amounts.values.sum()

    /** Mole fraction within *this phase*, 0..1. */
    fun moleFraction(id: String): Double {
        val total = totalMoles()
        return if (total <= 0.0) 0.0 else amount(id) / total
    }

    fun snapshot(): Map<String, Double> = LinkedHashMap(amounts)

    override fun toString(): String = "$kind($group: $amounts)"
}
