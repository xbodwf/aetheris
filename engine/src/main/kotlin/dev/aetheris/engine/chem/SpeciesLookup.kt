package dev.aetheris.engine.chem

/**
 * Turns what somebody types into species: an id, a bare name, or a formula.
 *
 * The formula path is the interesting one and it exists because the catalogue cannot hold most of what a
 * player will write. `HCl` is not a species and never will be - in solution it is H+ and Cl-, which is how
 * every reaction refers to it - so a formula is first tried as a substance and then **split** into the ions
 * that compose it. The order matters: `H2O` has to mean liquid water, and only the exact match knows that,
 * because asked as a decomposition it is also an acid plus a base.
 *
 * This lives in the engine rather than in the command so that it can be tested against the *shipped* data -
 * which is the only way to know that the formulas a player is likely to type actually resolve, as opposed to
 * resolving against a registry a test invented.
 */
object SpeciesLookup {

    /** Namespace a bare name belongs to, so `hydrogen_ion` means `aetheris:hydrogen_ion`. */
    const val DEFAULT_NAMESPACE = "aetheris"

    /**
     * Phases a solute may be read as, in the order somebody would mean them: dissolved, then solid, then
     * liquid. A gas is deliberately **not** in this list - see [contribution].
     */
    val NAMED_SOLUTE_PHASES = listOf(Phase.AQUEOUS, Phase.SOLID, Phase.LIQUID)

    /** By id, or by a bare name in this mod's namespace. A name with a namespace is taken at its word. */
    fun byName(registry: SpeciesRegistry, name: String): Species? =
        registry.get(name) ?: if (name.contains(':')) null else registry.get("$DEFAULT_NAMESPACE:$name")

    /**
     * What one entry contributes to a mixture: one substance, or the ions a formula decomposes into.
     *
     * The order of the attempts is the whole design, and each step earns its place:
     *
     *  1. a substance the catalogue holds in a phase somebody would mean - so `H2O` is liquid water and
     *     `Cu(OH)2` is the precipitate, since both exist and neither is meant as a pile of ions;
     *  2. **the decomposition**, before gases - because `HCl` *is* a gas species in the catalogue (hydrogen
     *     chloride, straight from the NASA set), and somebody writing it as a solute means hydrochloric acid.
     *     Putting gases last is what makes the catalogue's own entries not get in the way of the chemistry;
     *  3. a gas, for something like `CO2`, where there is no decomposition to be had and the gas is the
     *     substance.
     *
     * `null` means nothing could be made of the name at all.
     */
    fun contribution(registry: SpeciesRegistry, name: String): Map<String, Double>? {
        byName(registry, name)?.let { return mapOf(it.id to 1.0) }
        val formula = parse(name) ?: return null
        for (phase in NAMED_SOLUTE_PHASES) {
            registry.byFormula(formula, phase).firstOrNull()?.let { return mapOf(it.id to 1.0) }
        }
        FormulaSplit.decompose(formula, registry)?.let { return it }
        return registry.byFormula(formula, Phase.GAS).firstOrNull()?.let { mapOf(it.id to 1.0) }
    }

    /** A liquid by id, bare name or formula. A formula that is not a liquid is not a medium. */
    fun liquid(registry: SpeciesRegistry, name: String): Species? =
        byName(registry, name)
            ?: parse(name)?.let { formula -> registry.byFormula(formula, Phase.LIQUID).firstOrNull() }

    /** A formula, or `null` when the text is not one. */
    fun parse(text: String): Formula? =
        try {
            Formula.parse(text)
        } catch (_: FormulaException) {
            null
        }
}
