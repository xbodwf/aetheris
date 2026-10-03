package dev.aetheris.engine.world

/**
 * Archimedes, expressed in terms the rest of the engine already produces.
 *
 * A body feels an upward force equal to the weight of the medium it displaces. The engine can
 * already give both densities - a [dev.aetheris.engine.chem.Mixture] reports its own average
 * density, and [Atmosphere] reports the ambient air density at an altitude - so "does it float?"
 * is just a comparison, and it changes with altitude because the ambient density does.
 */
object Buoyancy {

    /** Standard gravity, m/s^2. */
    const val G = 9.80665

    /** Net upward force on a body, N. Positive means it rises. */
    fun netUpwardForce(volumeM3: Double, objectDensity: Double, ambientDensity: Double): Double =
        (ambientDensity - objectDensity) * G * volumeM3

    /** Upward acceleration of the body, m/s^2 (ignores drag). */
    fun acceleration(volumeM3: Double, objectDensity: Double, ambientDensity: Double): Double {
        val mass = objectDensity * volumeM3
        return if (mass <= 0.0) 0.0 else netUpwardForce(volumeM3, objectDensity, ambientDensity) / mass
    }

    /** True if a body of [objectDensity] floats in a medium of [ambientDensity]. */
    fun rises(objectDensity: Double, ambientDensity: Double): Boolean = objectDensity < ambientDensity
}
