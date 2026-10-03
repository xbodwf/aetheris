package dev.aetheris.engine.chem

/**
 * A rule that expands into concrete [Reaction]s against a registry.
 *
 * This is Aetheris's answer to "we are not Mendeleev": instead of hand-writing every
 * reaction, we encode a *pattern* (complete combustion, acid-base neutralisation, ...) once
 * and let it instantiate itself for every species that matches. One line of code covers a
 * whole family of molecules, and a new species added by a data pack automatically picks up
 * every applicable reaction.
 */
interface ReactionTemplate {
    val id: String

    /** All reactions this template can build from the species in [registry]. */
    fun instantiate(registry: SpeciesRegistry): List<Reaction>
}

/**
 * Complete combustion of any C/H/O species:
 *
 *     CxHyOz + (x + y/4 - z/2) O2 -> x CO2 + (y/2) H2O
 *
 * Coefficients are left fractional (an extent formulation tolerates halves) so the rate-law
 * order stays close to the physical one rather than ballooning. The same rule covers
 * hydrocarbons (`CH4`, `C2H6`), oxygenates (`CH3OH`, `C2H5OH`), hydrogen (`H2`) and carbon
 * monoxide (`CO`), because those are just the cases where x or y is zero.
 *
 * [preExponential] and [activationEnergy] are illustrative default Arrhenius parameters, not
 * literature values; they are meant to be replaced by real kinetic data per reaction.
 */
class CombustionTemplate(
    override val id: String = "aetheris:complete_combustion",
    private val oxygenId: String? = null,
    private val carbonDioxideId: String? = null,
    private val waterId: String? = null,
    private val activationEnergy: Double = 125_000.0,
    private val preExponential: Double = 1.0e6,
    private val phases: Set<Phase> = setOf(Phase.GAS, Phase.LIQUID),
) : ReactionTemplate {

    override fun instantiate(registry: SpeciesRegistry): List<Reaction> {
        val oxygenSpecies = resolveSpecies(registry, oxygenId, "O2")
        val carbonDioxideSpecies = resolveSpecies(registry, carbonDioxideId, "CO2")
        val waterSpecies = resolveSpecies(registry, waterId, "H2O")

        val reactions = ArrayList<Reaction>()
        for (species in registry.all) {
            if (species.phase !in phases) continue
            if (species.formula.charge != 0) continue
            if (!species.formula.only("C", "H", "O")) continue

            val carbon = species.formula.countOf("C")
            val hydrogen = species.formula.countOf("H")
            val oxygen = species.formula.countOf("O")
            if (carbon == 0 && hydrogen == 0) continue

            val oxygenCoefficient = carbon + hydrogen / 4.0 - oxygen / 2.0
            if (oxygenCoefficient <= 0.0) continue

            val reactants = linkedMapOf(oxygenSpecies to oxygenCoefficient, species.id to 1.0)
            val products = LinkedHashMap<String, Double>()
            if (carbon > 0) products[carbonDioxideSpecies] = carbon.toDouble()
            if (hydrogen > 0) products[waterSpecies] = hydrogen / 2.0

            reactions += Reaction(
                id = "$id/${species.id}",
                reactants = reactants,
                products = products,
                activationEnergy = activationEnergy,
                preExponential = preExponential,
                name = "Complete combustion of ${species.name}",
            )
        }
        return reactions
    }

    /**
     * Resolves a reaction participant either by an explicit id or, when none was given, by
     * formula and gas phase.
     *
     * Resolving by formula is what lets one template run unchanged over any catalogue: the
     * hand-curated defaults name oxygen `aetheris:oxygen`, while the NASA import names it
     * `aetheris:o2`, and the pattern should not care.
     */
    private fun resolveSpecies(registry: SpeciesRegistry, explicitId: String?, formula: String): String {
        if (explicitId != null) {
            require(registry.contains(explicitId)) { "Combustion requires species '$explicitId'" }
            return explicitId
        }
        return registry.byFormula(Formula.parse(formula), Phase.GAS).firstOrNull()?.id
            ?: throw IllegalArgumentException("Combustion requires a gaseous '$formula' species")
    }
}

/**
 * Precipitation and dissolution of sparingly soluble salts, as reversible reactions
 *
 *     sum(ions) <-> solid
 *
 * The point is that the solubility product is *not* authored: marking the reaction reversible
 * makes [ReactionEngine] derive `K = 1/Ksp` from the ions' and solid's formation enthalpies and
 * entropies, so the salt stops dissolving exactly where thermodynamics says it should.
 *
 * The ion -> solid pairing is authored because decomposing an arbitrary solid formula into its
 * constituent ions is beyond a formula-only engine; that decomposition is a later refinement.
 */
class PrecipitationTemplate(
    override val id: String = "aetheris:precipitation",
    private val rules: List<Rule>,
) : ReactionTemplate {

    data class Rule(
        val solid: String,
        val ions: Map<String, Double>,
        val activationEnergy: Double = 10_000.0,
        val preExponential: Double = 1.0e6,
    )

    override fun instantiate(registry: SpeciesRegistry): List<Reaction> = rules.map { rule ->
        require(registry.contains(rule.solid)) { "Precipitation requires solid '${rule.solid}'" }
        for (ion in rule.ions.keys) {
            require(registry.contains(ion)) { "Precipitation rule for '${rule.solid}' requires ion '$ion'" }
        }
        Reaction(
            id = "$id/${rule.solid}",
            reactants = rule.ions,
            products = mapOf(rule.solid to 1.0),
            activationEnergy = rule.activationEnergy,
            preExponential = rule.preExponential,
            reversible = true,
            name = "Precipitation of ${registry.require(rule.solid).name}",
        )
    }
}
