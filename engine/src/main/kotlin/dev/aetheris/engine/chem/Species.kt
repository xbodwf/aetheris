package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants

/**
 * A distinct chemical substance: one formula in one phase.
 *
 * Allotropes (graphite vs diamond) are separate species because the phase and the formation
 * enthalpy differ, even though the formula is the same. Isotopes are deliberately *not*
 * modelled here - they belong to a future nuclear/tracer layer, not to chemistry.
 *
 * [formationEnthalpy] is the standard enthalpy of formation at [Constants.T0] in J/mol
 * (negative = stable relative to the elements). [heatCapacity] is the molar heat capacity
 * cp in J/(mol*K), held constant for now; temperature-dependent cp can be introduced later
 * without changing any caller.
 *
 * @param entropy standard molar entropy in J/(mol*K), reserved for future Gibbs calculations.
 * @param solventGroup miscibility group for liquids: species sharing a group share one liquid
 *   phase. [Phase.AQUEOUS] defaults to `"water"`; a [Phase.LIQUID] with no group is its own phase
 *   (use the same group, e.g. `"organic"`, to let two liquids mix).
 * @param molarVolumeM3 explicit molar volume in m^3/mol; 0 means "estimate from phase and mass".
 * @param color ARGB tint used to render a solution; 0 means colourless (contributes nothing).
 */
data class Species(
    val id: String,
    val name: String,
    val formula: Formula,
    val phase: Phase,
    val formationEnthalpy: Double,
    val heatCapacity: Double,
    val entropy: Double = 0.0,
    val solventGroup: String? = null,
    val molarVolumeM3: Double = 0.0,
    val color: Int = 0,
) {
    init {
        require(id.contains(':')) { "Species id must be namespaced (namespace:path), was '$id'" }
        require(heatCapacity > 0.0) { "Heat capacity must be positive for '$id' (was $heatCapacity)" }
    }

    /** Molar mass in g/mol, derived from the formula. */
    val molarMass: Double get() = formula.molarMass

    /**
     * Molar volume in m^3/mol, used to size a condensed phase so molarity is meaningful.
     *
     * When [molarVolumeM3] is not given it is estimated from a typical density for the phase
     * (liquids ~1.0 g/cm^3, solids ~2.5 g/cm^3). Gases report 0 because their volume comes from
     * the cell's free headspace, not from the substance itself.
     */
    val molarVolume: Double
        get() = when {
            molarVolumeM3 > 0.0 -> molarVolumeM3
            phase == Phase.GAS -> 0.0
            phase == Phase.SOLID -> molarMass * 4e-7
            else -> molarMass * 1e-6
        }

    /**
     * Intrinsic density in kg/m^3, from molar mass and molar volume.
     *
     * This is the quantity buoyancy compares against the surrounding medium (a species denser
     * than the air sinks, one lighter rises). Gases report 0 because their density is not
     * intrinsic — it follows from temperature and pressure, so [Mixture] computes it from the
     * headspace instead.
     */
    val density: Double
        get() = if (molarVolume > 0.0) molarMass * 1e-3 / molarVolume else 0.0

    /**
     * Sensible molar internal energy at [temperature], J/mol:
     * `U(T) = hf + cp * (T - T0)`. This is the quantity the reaction step conserves.
     */
    fun internalEnergy(temperature: Double): Double =
        formationEnthalpy + heatCapacity * (temperature - Constants.T0)
}
