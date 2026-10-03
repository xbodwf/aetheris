package dev.aetheris.engine.world

import dev.aetheris.engine.Constants
import kotlin.math.exp

/**
 * Ambient air as a function of altitude: the boundary condition the gas field and buoyancy read
 * from. It is a barometric model, `p(z) = p0 * exp(-z / H)`, with the ideal-gas law turning
 * pressure into density.
 *
 * The vertical scale is deliberately explicit because the real atmosphere and a Minecraft world
 * disagree by orders of magnitude: Earth's scale height is ~8500 m, so over a ~384-block world a
 * literal 1 block = 1 m would thin the air by only a few percent and be invisible. [metresPerBlock]
 * and [scaleHeight] are the knobs that "划定" that; pick a smaller scale height (or larger
 * metres-per-block) to make rarefaction with altitude visible in play.
 */
data class Atmosphere(
    val seaLevelPressure: Double = Constants.P0,
    val temperature: Double = 288.15,
    val scaleHeight: Double = 8500.0,
    val metresPerBlock: Double = 1.0,
    val seaLevelY: Double = 62.0,
) {
    init {
        require(seaLevelPressure > 0.0) { "Sea-level pressure must be positive" }
        require(temperature > 0.0) { "Temperature must be positive" }
        require(scaleHeight > 0.0) { "Scale height must be positive" }
        require(metresPerBlock > 0.0) { "Metres per block must be positive" }
    }

    /** Pressure at [metres] above sea level, Pa. */
    fun pressureAtAltitude(metres: Double): Double = seaLevelPressure * exp(-metres / scaleHeight)

    /** Air density at [metres] above sea level, kg/m^3. */
    fun densityAtAltitude(metres: Double): Double =
        pressureAtAltitude(metres) * MEAN_AIR_MOLAR_MASS / (Constants.R * temperature)

    /** Air density at a Minecraft block height [y]. */
    fun densityAtBlock(y: Double): Double = densityAtAltitude((y - seaLevelY) * metresPerBlock)

    /** Air pressure at a Minecraft block height [y], Pa. */
    fun pressureAtBlock(y: Double): Double = pressureAtAltitude((y - seaLevelY) * metresPerBlock)

    companion object {
        /** Mean molar mass of dry air, kg/mol. */
        const val MEAN_AIR_MOLAR_MASS = 0.0289647

        /**
         * A gameplay atmosphere compressed so rarefaction is visible across a Minecraft build:
         * 1 block ~ 8 m and an 800 m scale height put sea level to build limit through ~1.5 scale
         * heights, so the air thins noticeably with altitude. Tune freely.
         */
        val MINECRAFT_LIKE = Atmosphere(metresPerBlock = 8.0, scaleHeight = 800.0)
    }
}
