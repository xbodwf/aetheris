package dev.aetheris.engine.world

import kotlin.math.floor

/**
 * Turns a fractional transport rate into whole steps, banking the remainder between ticks.
 *
 * The grid is discrete - a cell either spreads into a neighbour this tick or it does not - but the
 * thing being modelled is a rate. Viscosity makes that rate span a wide range: thin liquids move
 * faster than water, and a thick one may need hundreds of seconds to earn a single step. Modelling it
 * as a fraction of the contents moved per tick would force the rate to be clamped into (0, 1], which
 * both caps thin liquids at water's speed and turns "very viscous" into "moves half its contents
 * every tick", the opposite of the truth. Banking credit keeps the rate honest in both directions.
 *
 * Kept in the engine, with no Minecraft dependency, so the behaviour is directly testable.
 */
class FlowPacer(private val maxStepsPerTick: Int = DEFAULT_MAX_STEPS_PER_TICK) {

    init {
        require(maxStepsPerTick > 0) { "maxStepsPerTick must be positive (was $maxStepsPerTick)" }
    }

    private var credit = 0.0

    /** Credit banked so far, in steps; exposed for tests and diagnostics. */
    val banked: Double get() = credit

    /**
     * Whole spreading steps to take now, for a [rate] in steps per second.
     *
     * A rate above one returns that many steps; a rate below one returns zero until enough ticks have
     * accrued to pay for one. Steps beyond [maxStepsPerTick] are dropped rather than banked, so a
     * nonsense rate cannot make a single tick do unbounded work.
     */
    fun stepsFor(rate: Double): Int {
        if (rate > 0.0 && rate.isFinite()) credit += rate
        // Capping the bank *before* spending is what makes the limit a real one: capping only the
        // steps taken would leave the surplus banked and let a nonsense rate drain the world eight
        // cells per tick forever instead of once.
        credit = credit.coerceIn(0.0, maxStepsPerTick.toDouble())
        val steps = floor(credit).toInt()
        credit -= steps
        return steps
    }

    /**
     * Abandons banked credit.
     *
     * Called when the cell cannot move at all - it is too thin to spread, or walled in. Keeping the
     * credit would mean a cell that sat sealed for a minute suddenly discharging a minute's worth of
     * flow the instant a gap appeared.
     */
    fun reset() {
        credit = 0.0
    }

    companion object {
        /** Upper bound on spreading steps in one tick, so one cell cannot flood the world at once. */
        const val DEFAULT_MAX_STEPS_PER_TICK = 8
    }
}
