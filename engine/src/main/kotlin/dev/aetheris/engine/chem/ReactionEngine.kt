package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants

/** Safety bounds so a runaway step cannot produce negative or absurd temperatures. */
data class EngineConfig(
    val maxTemperature: Double = 6000.0,
    val minTemperature: Double = 1.0,
)

/** What one [ReactionEngine.step] did, for logging, tests and client-side feedback. */
data class StepReport(
    /** Extent of each reaction this step, mol (fractional because it is a rate * dt). */
    val extents: Map<String, Double>,
    val temperatureBefore: Double,
    val temperatureAfter: Double,
    val totalMolesBefore: Double,
    val totalMolesAfter: Double,
    /** Sum of `extent * enthalpyOfReaction`, J. Negative means the step released heat. */
    val reactionEnthalpy: Double,
    /** True if the adiabatic temperature had to be clamped by [EngineConfig]. */
    val temperatureClamped: Boolean,
) {
    val reacted: Boolean get() = extents.values.any { kotlin.math.abs(it) > 1e-12 }

    /** Heat released to the surroundings, J. */
    val heatReleased: Double get() = -reactionEnthalpy
}

/**
 * Advances chemistry inside a [Mixture] by a time step.
 *
 * The algorithm is the conservation heart of Aetheris:
 *
 *  1. Record the mixture's total sensible energy `U = sum(n_i * U_i(T))` before reacting.
 *  2. For each reaction compute the Arrhenius rate, integrate it over `dt`, and clamp the
 *     resulting extent to the limiting reactant so nothing goes negative.
 *  3. Apply the extents (`n_i += nu_i * xi`) and then solve for the new temperature that
 *     restores the energy recorded in step 1:
 *     `T = T0 + (U_before - sum(n_i * hf_i)) / sum(n_i * cp_i)`.
 *
 * The energy bookkeeping means heat is never invented: an exothermic reaction warms the
 * mixture, an endothermic one cools it, and both follow from the formation enthalpies rather
 * than from a hard-coded temperature delta. Reactions in one step share the temperature from
 * the start of the step (operator splitting); run smaller `dt` for higher accuracy.
 */
class ReactionEngine(
    val registry: SpeciesRegistry,
    val reactions: List<Reaction>,
    val config: EngineConfig = EngineConfig(),
) {
    private val reactionsById: Map<String, Reaction> = reactions.associateBy { it.id }
    private val enthalpies: Map<String, Double> =
        reactions.associate { it.id to it.standardEnthalpy(registry) }

    init {
        val duplicate = reactions.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Duplicate reaction id: ${duplicate?.key}" }
        for (reaction in reactions) {
            Conservation.requireBalanced(reaction, registry)
        }
    }

    /** Standard enthalpy of a registered reaction, J per mol of extent. */
    fun enthalpyOf(reactionId: String): Double =
        enthalpies[reactionId] ?: throw NoSuchElementException("Unknown reaction: '$reactionId'")

    fun step(mixture: Mixture, dt: Double): StepReport {
        require(dt > 0.0) { "dt must be positive (was $dt)" }

        val temperatureBefore = mixture.temperature
        val molesBefore = mixture.totalMoles()
        val energyBefore = mixture.internalEnergy()

        val extents = LinkedHashMap<String, Double>()
        for (reaction in reactions) {
            val volume = mixture.reactionVolume(reaction)
            val extent = if (reaction.reversible) {
                reversibleExtent(reaction, mixture, volume, dt)
            } else {
                // ratePerVolume is mol/(m^3*s); multiply by the reacting phase's volume for mol/s,
                // then by dt for mol. For an all-gas cell that phase is the whole cell.
                val limiting = reaction.limitingExtent(mixture)
                if (limiting <= 0.0) 0.0
                else minOf(reaction.ratePerVolume(mixture) * volume * dt, limiting)
            }
            if (extent != 0.0) reaction.apply(mixture, extent)
            extents[reaction.id] = extent
        }

        val molesAfter = mixture.totalMoles()
        val rawTemperature = if (molesAfter <= 0.0) {
            temperatureBefore
        } else {
            val enthalpyOfProducts = mixture.speciesIds
                .sumOf { mixture.amount(it) * registry.require(it).formationEnthalpy }
            val heatCapacity = mixture.heatCapacity()
            if (heatCapacity > 0.0) {
                Constants.T0 + (energyBefore - enthalpyOfProducts) / heatCapacity
            } else {
                temperatureBefore
            }
        }
        val clampedTemperature = rawTemperature.coerceIn(config.minTemperature, config.maxTemperature)
        val wasClamped = clampedTemperature != rawTemperature
        mixture.temperature = clampedTemperature

        var reactionEnthalpy = 0.0
        for ((id, extent) in extents) {
            if (extent > 0.0) reactionEnthalpy += extent * enthalpyOf(id)
        }

        return StepReport(
            extents = extents,
            temperatureBefore = temperatureBefore,
            temperatureAfter = clampedTemperature,
            totalMolesBefore = molesBefore,
            totalMolesAfter = molesAfter,
            reactionEnthalpy = reactionEnthalpy,
            temperatureClamped = wasClamped,
        )
    }

    /**
     * Signed extent for a reversible reaction: `(k_fwd * prod(a_reactants) - k_rev * prod(a_products)) * V * dt`
     * with `k_rev = k_fwd / K`. The net rate is zero exactly when `Q = K`, so the reaction
     * approaches equilibrium instead of running to completion. Negative means the reverse
     * direction won, which is how dissolution and endothermic back-reactions happen.
     *
     * Note these use the dimensionless [Mixture.standardActivity] (molarity in mol/L, p/P0),
     * the same convention as K, rather than the dimensional activity the one-way path uses.
     *
     * The forward term is also where nucleation applies, and only there: the barrier is the cost of *creating* a
     * condensed phase, so it is a gate on the direction that makes one and has nothing to say about a solid
     * dissolving. See [Nucleation].
     */
    private fun reversibleExtent(reaction: Reaction, mixture: Mixture, volume: Double, dt: Double): Double {
        val temperature = mixture.temperature
        if (temperature <= 0.0) return 0.0
        val kForward = reaction.preExponential * Math.exp(-reaction.effectiveActivationEnergy(mixture) / (Constants.R * temperature))
        val equilibrium = reaction.equilibriumConstant(registry, temperature)
        val kReverse = if (equilibrium > 0.0 && equilibrium.isFinite()) kForward / equilibrium else 0.0

        val nucleating = nucleationFactor(reaction, mixture, equilibrium, temperature)
        val forward = kForward * nucleating * sideDriving(reaction.reactants, mixture) * volume
        val reverse = kReverse * sideDriving(reaction.products, mixture) * volume
        val forwardLimit = reaction.limitingExtent(reaction.reactants, mixture)
        val reverseLimit = reaction.limitingExtent(reaction.products, mixture)
        return ((forward - reverse) * dt).coerceIn(-reverseLimit, forwardLimit)
    }

    /**
     * How much of the forward rate nucleation allows: 1.0 unless this reaction is *creating* a solid that is not
     * there yet.
     *
     * Any of the solid already present means there is a surface to grow on, and growth clears no barrier - which
     * is exactly why dropping a seed crystal into a metastable solution precipitates it at once.
     *
     * The supersaturation ratio is the **ion product over the solubility product**, and the reaction's own K is
     * `1/Ksp`, so it is `Q * K` rather than `Q / K`. Dividing instead multiplies by Ksp squared - about 1e-16 for
     * calcite - which puts every solution far *below* saturation and lets the barrier wave everything through.
     * That is a mistake worth naming, because it fails silently in the direction of "precipitation always
     * happens", which is exactly what the barrier was added to stop.
     */
    private fun nucleationFactor(
        reaction: Reaction,
        mixture: Mixture,
        equilibrium: Double,
        temperature: Double,
    ): Double {
        if (equilibrium <= 0.0 || !equilibrium.isFinite()) return 1.0
        val solid = reaction.products.keys.firstOrNull { registry.require(it).phase == Phase.SOLID }
            ?: return 1.0
        if (mixture.amount(solid) > 0.0) return 1.0
        val supersaturation = sideDriving(reaction.reactants, mixture) * equilibrium
        return Nucleation.factor(supersaturation, registry.require(solid).molarVolume, temperature)
    }

    /** Product of standard activities raised to their coefficients; 0 if any is absent. */
    private fun sideDriving(side: Map<String, Double>, mixture: Mixture): Double {
        var driving = 1.0
        for ((id, nu) in side) {
            val activity = mixture.standardActivity(id)
            if (activity <= 0.0) return 0.0
            driving *= Math.pow(activity, nu)
        }
        return driving
    }

    /**
     * Gives a metastable solution something to crystallise on, and reports what took.
     *
     * This is agitation, a scratch on the glass, a speck of dust - anything that supplies a surface. The physics
     * is [Nucleation]'s: the barrier is the cost of *creating* a phase, and a surface skips it entirely, so the
     * honest model of shaking a tube is to put a seed in it. One mole in a billion is enough to stop being
     * nucleation and start being growth.
     *
     * Only reactions whose solid is **absent** are considered, and only where the solution is actually
     * supersaturated - agitating a solution with nothing to give would be theatre.
     */
    fun seed(mixture: Mixture): List<String> {
        val formed = ArrayList<String>()
        for (reaction in reactions) {
            if (!reaction.reversible) continue
            val solid = reaction.products.keys.firstOrNull { registry.require(it).phase == Phase.SOLID }
                ?: continue
            if (mixture.amount(solid) > 0.0) continue
            val equilibrium = reaction.equilibriumConstant(registry, mixture.temperature)
            if (equilibrium <= 0.0 || !equilibrium.isFinite()) continue
            val supersaturation = sideDriving(reaction.reactants, mixture) * equilibrium
            if (supersaturation <= 1.0) continue
            mixture.add(solid, SEED_MOLES)
            formed += solid
        }
        return formed
    }

    /** Runs [steps] steps of [dt] seconds, returning every report. */
    fun simulate(mixture: Mixture, dt: Double, steps: Int): List<StepReport> {
        require(steps >= 0) { "steps must be non-negative (was $steps)" }
        return (0 until steps).map { step(mixture, dt) }
    }

    fun reaction(id: String): Reaction? = reactionsById[id]

    private companion object {
        /**
         * A seed's worth: a billionth of a mole.
         *
         * Small enough to be invisible in any readout and in the fill fraction, and large enough that the next
         * step sees `amount(solid) > 0` and stops applying a nucleation barrier. Anything larger would be adding
         * matter rather than merely giving it somewhere to form.
         */
        const val SEED_MOLES = 1.0e-9
    }
}
