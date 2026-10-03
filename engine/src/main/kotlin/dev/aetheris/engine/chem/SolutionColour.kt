package dev.aetheris.engine.chem

/**
 * Derives "is this ion coloured?" from the periodic table instead of hand-authoring it.
 *
 * A simple transition-metal cation shows d-d colour only when its d subshell is partially filled
 * (d1..d9); d0 and d10 ions (Sc^3+, Zn^2+, Cu^+) and all main-group ions are colourless. The
 * d-electron count is group - oxidation state, both of which come from the element table and the
 * formula charge. What this buys is not having to author colour for the *colourless majority*:
 * only the hue of the genuinely coloured ions is irreducible, because hue comes from ligand-field
 * splittings the engine cannot compute.
 *
 * Known miss: charge-transfer colours (permanganate MnO4^-, dichromate Cr2O7^2-) are not d-d
 * transitions, so the rule calls them colourless; keep listing those in the hue table explicitly.
 */
object SolutionColour {

    /** True if a simple cation of [symbol] with oxidation number [charge] should show d-d colour. */
    fun isDdColoured(symbol: String, charge: Int): Boolean {
        if (charge <= 0) return false
        val group = Elements.dBlockGroup(Elements.require(symbol).atomicNumber) ?: return false
        val electrons = group - charge
        return electrons in 1..9
    }

    /**
     * True when [species] contains a transition metal that, at the formula's charge, should be
     * coloured. Neutral or heteroleptic compounds fall back to false because their metal's
     * oxidation state is not the formula charge; model such solutions as ions to get colour.
     */
    fun expectsColour(species: Species): Boolean {
        val charge = species.formula.charge
        return species.formula.counts.keys.any { symbol ->
            Elements.of(symbol)?.let { isDdColoured(symbol, charge) } ?: false
        }
    }

    /**
     * Species that should be coloured but carry no [Species.color] - exactly the data a packmaker
     * still has to supply. Meant for a load-time warning, never for guessing a hue.
     */
    fun missingColour(registry: SpeciesRegistry): List<Species> =
        registry.all.filter { it.color == 0 && expectsColour(it) }
}
