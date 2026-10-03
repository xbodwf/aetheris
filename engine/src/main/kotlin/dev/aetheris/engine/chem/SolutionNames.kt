package dev.aetheris.engine.chem

/**
 * Builds a human-facing description of what a solution contains, so an info mod (Jade, TOP) can
 * show "CuSO4 + FeSO4" instead of the generic block name.
 *
 * The solvent (liquid-phase water) is left out; what a player cares about is the dissolved
 * solutes, precipitates and any gas, listed by amount. Formulas are used rather than English
 * names because they are language-neutral and always available, even for species a data pack
 * added without a translation.
 */
object SolutionNames {

    /** One described species: its formula and amount. */
    data class Entry(val formula: String, val moles: Double, val phase: Phase)

    /** The non-solvent contents of [mixture], largest first. */
    fun contents(mixture: Mixture, limit: Int = 4): List<Entry> =
        mixture.speciesIds
            .mapNotNull { id ->
                val species = mixture.registry.require(id)
                val moles = mixture.amount(id)
                if (moles <= 1e-9 || species.phase == Phase.LIQUID) null
                else Entry(species.formula.toString(), moles, species.phase)
            }
            .sortedByDescending { it.moles }
            .take(limit)

    /** A compact summary such as `Cu^2+ 1 mol + SO4^2- 1 mol`, or `empty` for pure solvent. */
    fun describe(mixture: Mixture, limit: Int = 4): String {
        val entries = contents(mixture, limit)
        if (entries.isEmpty()) return "empty"
        return entries.joinToString(" + ") { "${it.formula} ${"%.3g".format(it.moles)} mol" }
    }
}
