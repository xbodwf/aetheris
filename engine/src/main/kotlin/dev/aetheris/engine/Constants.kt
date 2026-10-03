package dev.aetheris.engine

/**
 * Physical constants, all SI.
 *
 * Every quantity inside Aetheris is expressed in one of these units so that the
 * conservation laws (mass and energy) are checked dimensionally the same way everywhere:
 *
 *  - amount      mol
 *  - temperature K
 *  - volume      m^3
 *  - pressure    Pa
 *  - energy      J
 *  - time        s
 */
object Constants {
    /** Universal gas constant, J/(mol*K). */
    const val R: Double = 8.314462618

    /** Standard reference temperature (25 C), K. Formation enthalpies are quoted here. */
    const val T0: Double = 298.15

    /** Standard pressure (1 atm), Pa. */
    const val P0: Double = 101_325.0

    /** Offset between Kelvin and Celsius. */
    const val KELVIN_AT_ZERO_CELSIUS: Double = 273.15

    /** Avogadro constant, 1/mol. */
    const val AVOGADRO: Double = 6.02214076e23
}
