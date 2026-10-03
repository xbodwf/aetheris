package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants

/**
 * A stoichiometric reaction with an Arrhenius forward rate.
 *
 * Both sides are species id to stoichiometric coefficient. The rate law is
 * `r = A * exp(-Ea / (R*T)) * product(activity_i ^ nu_i)`, with activity supplied by
 * [Mixture.activity]. [preExponential] therefore carries whatever units make `r` come out in
 * mol/(m^3*s) for the given order; the values shipped in [SpeciesRegistry] are illustrative
 * starting points meant to be tuned against data, not literature rate constants.
 *
 * Enthalpy is never stored: it is derived from the formation enthalpies of the participants
 * (Hess's law), so the engine and the thermodynamics can never disagree.
 */
data class Reaction(
    val id: String,
    val reactants: Map<String, Double>,
    val products: Map<String, Double>,
    val activationEnergy: Double,
    val preExponential: Double,
    val name: String = id,
    /**
     * When true the reaction runs both ways: the engine adds a reverse rate derived from the
     * equilibrium constant, so the step drives the mixture toward `Q = K` instead of consuming
     * the reactants outright. Defaults to false, leaving the classic one-way Arrhenius behaviour.
     */
    val reversible: Boolean = false,
    /**
     * Optional catalyst species id. It is not consumed: when present it lowers the effective
     * activation energy by [catalystActivationReduction], which is how a catalyst changes a rate
     * without changing the thermodynamics (K, and therefore the equilibrium point, is untouched).
     */
    val catalyst: String? = null,
    /** Activation-energy reduction in J/mol while [catalyst] is present; ignored if negative. */
    val catalystActivationReduction: Double = 0.0,
) {
    init {
        require(id.isNotBlank()) { "Reaction id must not be blank" }
        require(reactants.isNotEmpty()) { "Reaction '$id' has no reactants" }
        require(products.isNotEmpty()) { "Reaction '$id' has no products" }
        require(reactants.values.all { it > 0.0 }) { "Reactant coefficients must be positive in '$id'" }
        require(products.values.all { it > 0.0 }) { "Product coefficients must be positive in '$id'" }
        require(activationEnergy >= 0.0) { "Activation energy must be non-negative in '$id'" }
        require(preExponential > 0.0) { "Pre-exponential factor must be positive in '$id'" }
        require(catalystActivationReduction >= 0.0) {
            "Catalyst activation reduction must be non-negative in '$id'"
        }
        require(catalyst == null || catalyst !in reactants) {
            "Catalyst '$catalyst' must not also be a reactant in '$id' (it is not consumed)"
        }
    }

    /**
     * Activation energy actually felt at [mixture]'s current composition: reduced by the catalyst
     * when one is present, otherwise the intrinsic value.
     */
    fun effectiveActivationEnergy(mixture: Mixture): Double {
        val species = catalyst ?: return activationEnergy
        if (mixture.amount(species) <= 0.0) return activationEnergy
        return (activationEnergy - catalystActivationReduction).coerceAtLeast(0.0)
    }

    /** Sum of reactant coefficients, i.e. the overall reaction order of the forward law. */
    val order: Double = reactants.values.sum()

    /** Standard enthalpy of reaction, J per mol of extent. Negative means exothermic. */
    fun standardEnthalpy(registry: SpeciesRegistry): Double {
        var h = 0.0
        for ((id, nu) in products) h += nu * registry.require(id).formationEnthalpy
        for ((id, nu) in reactants) h -= nu * registry.require(id).formationEnthalpy
        return h
    }

    /** Standard entropy of reaction, J/(mol*K): products minus reactants. */
    fun standardEntropy(registry: SpeciesRegistry): Double {
        var s = 0.0
        for ((id, nu) in products) s += nu * registry.require(id).entropy
        for ((id, nu) in reactants) s -= nu * registry.require(id).entropy
        return s
    }

    /** Standard Gibbs energy of reaction at [temperature], J/mol: `dG = dH - T dS`. */
    fun standardGibbs(registry: SpeciesRegistry, temperature: Double): Double =
        standardEnthalpy(registry) - temperature * standardEntropy(registry)

    /**
     * Equilibrium constant at [temperature] from `K = exp(-dG / (R T))`.
     *
     * The activities behind `K` are the standard-state ones ([Mixture.standardActivity]): gases
     * as p/P0, solutes as molarity in mol/L, pure solids as 1. Saturation is then simply
     * `Q = K`, which is what lets a sparingly soluble salt stop precipitating on its own - the
     * "solubility product" is not authored, it falls out of the formation enthalpies and
     * entropies. Guarded against overflow so an absurd dG cannot produce a NaN rate.
     */
    fun equilibriumConstant(registry: SpeciesRegistry, temperature: Double): Double {
        val exponent = -standardGibbs(registry, temperature) / (Constants.R * temperature)
        return when {
            exponent > 709.0 -> Double.MAX_VALUE
            exponent < -709.0 -> 0.0
            else -> Math.exp(exponent)
        }
    }

    /** Largest extent, in mol, before the first reactant runs out. */
    fun limitingExtent(mixture: Mixture): Double = limitingExtent(reactants, mixture)

    /** Largest extent, in mol, before any species on [side] runs out. */
    fun limitingExtent(side: Map<String, Double>, mixture: Mixture): Double {
        var limit = Double.MAX_VALUE
        for ((id, nu) in side) {
            limit = minOf(limit, mixture.amount(id) / nu)
        }
        return maxOf(limit, 0.0)
    }

    /** Forward reaction rate per unit volume, mol/(m^3*s). Zero if any reactant is absent. */
    fun ratePerVolume(mixture: Mixture): Double {
        val t = mixture.temperature
        if (t <= 0.0) return 0.0
        var driving = 1.0
        for ((id, nu) in reactants) {
            val activity = mixture.activity(id)
            if (activity <= 0.0) return 0.0
            driving *= Math.pow(activity, nu)
        }
        val k = preExponential * Math.exp(-effectiveActivationEnergy(mixture) / (Constants.R * t))
        return k * driving
    }

    /** Moves [extent] mol of reaction, adjusting every participant (negative runs it backwards). */
    fun apply(mixture: Mixture, extent: Double) {
        if (extent == 0.0) return
        for ((id, nu) in reactants) mixture.add(id, -nu * extent)
        for ((id, nu) in products) mixture.add(id, nu * extent)
    }

    override fun toString(): String =
        reactants.entries.joinToString(" + ") { (id, n) -> "${trim(n)} $id" } +
            " -> " +
            products.entries.joinToString(" + ") { (id, n) -> "${trim(n)} $id" }

    private fun trim(n: Double): String =
        if (n == Math.floor(n)) n.toLong().toString() else n.toString()
}
