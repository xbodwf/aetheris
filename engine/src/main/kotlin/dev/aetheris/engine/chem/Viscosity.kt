package dev.aetheris.engine.chem

import kotlin.math.exp
import kotlin.math.ln

/**
 * How thick a liquid is, and therefore how fast it moves.
 *
 * Viscosity is what makes honey crawl and water race, so it is the property that turns "how fast does
 * this solution flow" into a consequence of the chemistry rather than a constant. Gravity-driven
 * laminar flow - a film running down a wall, liquid draining through a gap - has a volumetric rate
 * inversely proportional to dynamic viscosity (Nusselt film theory, and Hagen-Poiseuille for flow
 * through a channel), so [flowScale] is simply the reciprocal of viscosity in units of water's.
 *
 * Values are dynamic viscosity in Pa*s at 25 C, which is the unit the tables use once converted from
 * the centipoise a chemist would quote.
 */
object Viscosity {

    /** Dynamic viscosity of liquid water at 25 C, Pa*s: the reference every flow rate is relative to. */
    const val WATER = 8.9e-4

    /**
     * Slowest a liquid may move relative to water.
     *
     * The physical ratio is unbounded - glycerol is ~1600x water and pitch is 10^11x - and taking it
     * literally would leave a thick liquid frozen rather than slow, which a player cannot tell apart
     * from a bug. This floor keeps it creeping instead: one spreading step roughly every four minutes.
     */
    const val MIN_FLOW_SCALE = 1.0 / 256.0

    /**
     * Viscosity of a mixture, Pa*s, by the Arrhenius (Kendall) log-mole-fraction rule
     * `ln(mu) = sum(x_i * ln(mu_i))`.
     *
     * The rule is a real one, and the reason to use it rather than a linear average is that viscosity
     * spans orders of magnitude: a linear mean is dominated by its thickest component and badly
     * over-thickens everything else. 10 mol% glycerol in water comes out at ~1.9 mPa*s here, against
     * ~142 from a linear average and ~1.4 in the literature - so the log rule is the one that keeps a
     * splash of something thick from turning a whole cell into treacle. Components with no tabulated
     * viscosity are left out of the average rather than guessed at, so an unknown solute neither
     * thickens nor thins the result.
     *
     * @param components mole fraction against viscosity in Pa*s; fractions need not sum to one.
     * @return the mixture viscosity, or `null` when nothing in the mixture declared one.
     */
    fun mixture(components: List<Pair<Double, Double>>): Double? {
        var logSum = 0.0
        var weight = 0.0
        for ((fraction, viscosity) in components) {
            if (fraction <= 0.0 || viscosity <= 0.0) continue
            logSum += fraction * ln(viscosity)
            weight += fraction
        }
        if (weight <= 0.0) return null
        return exp(logSum / weight)
    }

    /**
     * How fast a liquid of this [viscosity] moves, as a multiple of water's rate: `mu_water / mu`,
     * floored at [MIN_FLOW_SCALE].
     *
     * The result is a *rate*, not a fraction, so it is deliberately not capped at one: acetone and
     * other thin liquids run faster than water, and the caller is expected to spend the rate in whole
     * steps rather than to clamp it. An unknown or non-positive viscosity is treated as water.
     */
    fun flowScale(viscosity: Double): Double =
        if (viscosity <= 0.0) 1.0 else (WATER / viscosity).coerceAtLeast(MIN_FLOW_SCALE)

    /** True when a viscosity in Pa*s is worth reading from data at all. */
    fun isUsable(viscosity: Double): Boolean = viscosity > 0.0
}
