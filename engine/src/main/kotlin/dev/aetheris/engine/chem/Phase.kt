package dev.aetheris.engine.chem

/**
 * State of aggregation of a species.
 *
 * [isCondensed] distinguishes gases (whose thermodynamic activity is a partial pressure)
 * from condensed phases (whose activity is approximated by mole fraction until real
 * solution models land).
 */
enum class Phase(val isCondensed: Boolean) {
    GAS(false),
    LIQUID(true),
    SOLID(true),
    AQUEOUS(true),
}
