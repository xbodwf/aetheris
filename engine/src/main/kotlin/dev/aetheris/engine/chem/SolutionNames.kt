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

    /**
     * One readable line of a solution: what it is, how much of it, and how strong it is.
     *
     * [molarity] is the number a chemist actually reads off a bench; [moles] is what matters for what has
     * settled out, since a solid has no meaningful concentration.
     */
    data class Reading(
        val id: String,
        /** Machine-readable, as the parser would accept it back. */
        val formula: String,
        /** The same formula with subscripts and superscripts, for showing to a person. */
        val prettyFormula: String,
        val phase: Phase,
        val moles: Double,
        /** mol/L inside the phase holding it, or 0 for anything that is not dissolved. */
        val molarity: Double,
        /** The species' own tint, or 0 when it has none, so a readout can colour its own lines. */
        val color: Int,
    )

    /**
     * The dissolved and settled contents of [mixture], largest first, with the numbers a readout needs.
     *
     * The solvent itself is dropped, and it is identified as *the largest liquid* rather than by name -
     * which is what makes this work for a non-water medium too. Pure glycerol then reads as "nothing
     * dissolved in it", which is the truth, instead of reporting the medium as if it were a solute.
     */
    fun readout(mixture: Mixture, limit: Int = 5): List<Reading> {
        val everything = mixture.speciesIds.mapNotNull { id ->
            val species = mixture.registry.require(id)
            val moles = mixture.amount(id)
            if (moles <= 1e-9) return@mapNotNull null
            Reading(
                id = id,
                formula = species.formula.toString(),
                prettyFormula = species.formula.pretty(),
                phase = species.phase,
                moles = moles,
                molarity = mixture.molarity(id),
                color = species.color,
            )
        }
        val solvent = everything.filter { it.phase == Phase.LIQUID }.maxByOrNull { it.moles }?.id
        return everything
            .filter { it.id != solvent }
            .sortedByDescending { it.moles }
            .take(limit)
    }
}
