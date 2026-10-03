package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * Classical nucleation theory: the physics behind "why does a supersaturated solution sometimes just sit there".
 *
 * Forming the first crystal is not free. A nucleus of radius `r` pays `4*pi*r^2*gamma` in surface energy and is
 * repaid `(4/3)*pi*r^3*dGv` by the bulk, so the sum peaks at the critical radius and the height of that peak is
 *
 *     dG* = 16*pi*gamma^3 / (3*dGv^2),   dGv = R*T*ln(S)/Vm
 *
 * where `S` is the supersaturation ratio and `Vm` the solid's molar volume. Nuclei appear at a rate proportional
 * to `exp(-dG* / (k_B T))`, so the barrier does not soften the rate - it switches it off, and sharply.
 * That sharpness is the whole thing worth having:
 *
 *  - twice supersaturated, calcite: `dG*` is nearly two thousand times the thermal energy, so the exponent
 *    underflows and *nothing happens*, for as long as anybody cares to watch;
 *  - a hundred million times supersaturated - a mole of calcium and a mole of carbonate dropped into a litre -
 *    it is a couple of thermal energies, and the solid appears at once.
 *
 * Seawater is three to six times supersaturated in calcite and does not spontaneously precipitate, which is the
 * real-world statement of the same fact and the reason a reef is not a rock.
 *
 * Only the **creation of a new phase** is hindered. Any solid already present is a surface to grow on, so growth
 * clears no barrier at all - which is what makes a seed crystal do what a seed crystal does.
 *
 * `gamma` for a mineral in water runs about 0.05-0.2 J/m^2, and one value serves every solid here rather than one
 * per substance. That is the same kind of approximation the catalogue already makes for ion heat capacities and
 * for the activity model, and it is why the *thresholds* this produces are approximate while the shape of the
 * behaviour is not.
 */
object Nucleation {

    /** Interfacial energy of a mineral in its own solution, J/m^2. */
    const val INTERFACIAL_ENERGY = 0.1

    /** Boltzmann's constant, J/K, from the gas constant and Avogadro's number. */
    private val BOLTZMANN = Constants.R / AVOGADRO

    private const val AVOGADRO = 6.02214076e23

    /** `dG* / (k_B T)`, dimensionless: the barrier measured in thermal energy. */
    fun criticalBarrier(supersaturation: Double, molarVolume: Double, temperature: Double): Double {
        require(supersaturation > 1.0) { "A barrier only exists above saturation (was $supersaturation)" }
        require(molarVolume > 0.0) { "A solid needs a molar volume (was $molarVolume)" }
        require(temperature > 0.0) { "Temperature must be positive (was $temperature)" }

        val bulk = Constants.R * temperature * ln(supersaturation) / molarVolume
        val surface = 16.0 * PI * INTERFACIAL_ENERGY * INTERFACIAL_ENERGY * INTERFACIAL_ENERGY
        return surface / (3.0 * bulk * bulk) / (BOLTZMANN * temperature)
    }

    /**
     * The fraction of the unhindered forward rate that nuclei allow.
     *
     * Returns 1.0 where there is no barrier to speak of, which includes a solution that is not supersaturated at
     * all: there the driving force is already backwards, so there is nothing for a barrier to hold off.
     */
    fun factor(supersaturation: Double, molarVolume: Double, temperature: Double): Double {
        if (supersaturation <= 1.0 || molarVolume <= 0.0 || temperature <= 0.0) return 1.0
        val barrier = criticalBarrier(supersaturation, molarVolume, temperature)
        if (barrier <= 0.0) return 1.0
        // Underflows to exactly zero for anything mildly supersaturated. That is the intent: metastable, not
        // merely slow.
        return exp(-barrier)
    }
}
