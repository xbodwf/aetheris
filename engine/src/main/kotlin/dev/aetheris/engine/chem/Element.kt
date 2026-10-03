package dev.aetheris.engine.chem

/**
 * A chemical element.
 *
 * Elements are the alphabet of chemistry rather than substances, so the periodic table is
 * the *only* thing Aetheris hard-codes. Everything else - molar mass, reaction balance,
 * heat of reaction - is derived from formulas written in terms of these entries. That is
 * what keeps the mod from turning into a hand-maintained substance list: new molecules are
 * data, not code.
 *
 * [atomicMass] is the conventional standard atomic weight in g/mol, which is numerically
 * the molar mass in kg/mol * 1000; since the engine works in J/mol we treat it as g/mol.
 */
data class Element(
    val symbol: String,
    val atomicNumber: Int,
    val atomicMass: Double,
    val name: String,
)

object Elements {
    private val table: List<Element> = listOf(
        Element("H", 1, 1.008, "Hydrogen"),
        Element("He", 2, 4.0026, "Helium"),
        Element("Li", 3, 6.94, "Lithium"),
        Element("Be", 4, 9.0122, "Beryllium"),
        Element("B", 5, 10.81, "Boron"),
        Element("C", 6, 12.011, "Carbon"),
        Element("N", 7, 14.007, "Nitrogen"),
        Element("O", 8, 15.999, "Oxygen"),
        Element("F", 9, 18.998, "Fluorine"),
        Element("Ne", 10, 20.180, "Neon"),
        Element("Na", 11, 22.990, "Sodium"),
        Element("Mg", 12, 24.305, "Magnesium"),
        Element("Al", 13, 26.982, "Aluminium"),
        Element("Si", 14, 28.085, "Silicon"),
        Element("P", 15, 30.974, "Phosphorus"),
        Element("S", 16, 32.06, "Sulfur"),
        Element("Cl", 17, 35.45, "Chlorine"),
        Element("Ar", 18, 39.948, "Argon"),
        Element("K", 19, 39.098, "Potassium"),
        Element("Ca", 20, 40.078, "Calcium"),
        Element("Sc", 21, 44.956, "Scandium"),
        Element("Ti", 22, 47.867, "Titanium"),
        Element("V", 23, 50.942, "Vanadium"),
        Element("Cr", 24, 51.996, "Chromium"),
        Element("Mn", 25, 54.938, "Manganese"),
        Element("Fe", 26, 55.845, "Iron"),
        Element("Co", 27, 58.933, "Cobalt"),
        Element("Ni", 28, 58.693, "Nickel"),
        Element("Cu", 29, 63.546, "Copper"),
        Element("Zn", 30, 65.38, "Zinc"),
        Element("Ga", 31, 69.723, "Gallium"),
        Element("Ge", 32, 72.630, "Germanium"),
        Element("As", 33, 74.922, "Arsenic"),
        Element("Se", 34, 78.971, "Selenium"),
        Element("Br", 35, 79.904, "Bromine"),
        Element("Kr", 36, 83.798, "Krypton"),
        Element("Rb", 37, 85.468, "Rubidium"),
        Element("Sr", 38, 87.62, "Strontium"),
        Element("Y", 39, 88.906, "Yttrium"),
        Element("Zr", 40, 91.224, "Zirconium"),
        Element("Nb", 41, 92.906, "Niobium"),
        Element("Mo", 42, 95.95, "Molybdenum"),
        Element("Tc", 43, 98.0, "Technetium"),
        Element("Ru", 44, 101.07, "Ruthenium"),
        Element("Rh", 45, 102.91, "Rhodium"),
        Element("Pd", 46, 106.42, "Palladium"),
        Element("Ag", 47, 107.87, "Silver"),
        Element("Cd", 48, 112.41, "Cadmium"),
        Element("In", 49, 114.82, "Indium"),
        Element("Sn", 50, 118.71, "Tin"),
        Element("Sb", 51, 121.76, "Antimony"),
        Element("Te", 52, 127.60, "Tellurium"),
        Element("I", 53, 126.90, "Iodine"),
        Element("Xe", 54, 131.29, "Xenon"),
        Element("Cs", 55, 132.91, "Caesium"),
        Element("Ba", 56, 137.33, "Barium"),
        Element("La", 57, 138.91, "Lanthanum"),
        Element("Ce", 58, 140.12, "Cerium"),
        Element("Pr", 59, 140.91, "Praseodymium"),
        Element("Nd", 60, 144.24, "Neodymium"),
        Element("Pm", 61, 145.0, "Promethium"),
        Element("Sm", 62, 150.36, "Samarium"),
        Element("Eu", 63, 151.96, "Europium"),
        Element("Gd", 64, 157.25, "Gadolinium"),
        Element("Tb", 65, 158.93, "Terbium"),
        Element("Dy", 66, 162.50, "Dysprosium"),
        Element("Ho", 67, 164.93, "Holmium"),
        Element("Er", 68, 167.26, "Erbium"),
        Element("Tm", 69, 168.93, "Thulium"),
        Element("Yb", 70, 173.05, "Ytterbium"),
        Element("Lu", 71, 174.97, "Lutetium"),
        Element("Hf", 72, 178.49, "Hafnium"),
        Element("Ta", 73, 180.95, "Tantalum"),
        Element("W", 74, 183.84, "Tungsten"),
        Element("Re", 75, 186.21, "Rhenium"),
        Element("Os", 76, 190.23, "Osmium"),
        Element("Ir", 77, 192.22, "Iridium"), //嘻嘻
        Element("Pt", 78, 195.08, "Platinum"),
        Element("Au", 79, 196.97, "Gold"),
        Element("Hg", 80, 200.59, "Mercury"),
        Element("Tl", 81, 204.38, "Thallium"),
        Element("Pb", 82, 207.2, "Lead"),
        Element("Bi", 83, 208.98, "Bismuth"),
        Element("Po", 84, 209.0, "Polonium"),
        Element("At", 85, 210.0, "Astatine"),
        Element("Rn", 86, 222.0, "Radon"),
        Element("Fr", 87, 223.0, "Francium"),
        Element("Ra", 88, 226.0, "Radium"),
        Element("Ac", 89, 227.0, "Actinium"),
        Element("Th", 90, 232.04, "Thorium"),
        Element("Pa", 91, 231.04, "Protactinium"),
        Element("U", 92, 238.03, "Uranium"),
        Element("Np", 93, 237.0, "Neptunium"),
        Element("Pu", 94, 244.0, "Plutonium"),
        Element("Am", 95, 243.0, "Americium"),
        Element("Cm", 96, 247.0, "Curium"),
        Element("Bk", 97, 247.0, "Berkelium"),
        Element("Cf", 98, 251.0, "Californium"),
        Element("Es", 99, 252.0, "Einsteinium"),
        Element("Fm", 100, 257.0, "Fermium"),
        Element("Md", 101, 258.0, "Mendelevium"),
        Element("No", 102, 259.0, "Nobelium"),
        Element("Lr", 103, 266.0, "Lawrencium"),
        Element("Rf", 104, 267.0, "Rutherfordium"),
        Element("Db", 105, 268.0, "Dubnium"),
        Element("Sg", 106, 269.0, "Seaborgium"),
        Element("Bh", 107, 270.0, "Bohrium"),
        Element("Hs", 108, 277.0, "Hassium"),
        Element("Mt", 109, 278.0, "Meitnerium"),
        Element("Ds", 110, 281.0, "Darmstadtium"),
        Element("Rg", 111, 282.0, "Roentgenium"),
        Element("Cn", 112, 285.0, "Copernicium"),
        Element("Nh", 113, 286.0, "Nihonium"),
        Element("Fl", 114, 289.0, "Flerovium"),
        Element("Mc", 115, 290.0, "Moscovium"),
        Element("Lv", 116, 293.0, "Livermorium"),
        Element("Ts", 117, 294.0, "Tennessine"),
        Element("Og", 118, 294.0, "Oganesson"),
    )

    private val bySymbol: Map<String, Element> = table.associateBy { it.symbol }

    val all: List<Element> get() = table
    val size: Int get() = table.size

    fun of(symbol: String): Element? = bySymbol[symbol]

    fun require(symbol: String): Element =
        bySymbol[symbol] ?: throw NoSuchElementException("Unknown element symbol: '$symbol'")

    operator fun get(symbol: String): Element? = of(symbol)

    // --- Periodic structure (lets the engine derive which ions are coloured) ----------------

    /** True for a d-block (transition-metal) element: Sc-Zn, Y-Cd, Hf-Hg, Rf-Cn. */
    fun isTransitionMetal(symbol: String): Boolean = dBlockGroup(require(symbol).atomicNumber) != null

    /** Group number 3..12 for a d-block atomic number, or null outside the d-block. */
    fun dBlockGroup(atomicNumber: Int): Int? = when (atomicNumber) {
        in 21..30 -> atomicNumber - 18
        in 39..48 -> atomicNumber - 36
        in 72..80 -> atomicNumber - 68
        in 104..112 -> atomicNumber - 100
        else -> null
    }
}
