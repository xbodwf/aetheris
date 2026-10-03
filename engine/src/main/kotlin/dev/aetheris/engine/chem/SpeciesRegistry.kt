package dev.aetheris.engine.chem

/**
 * A catalogue of [Species] keyed by namespaced id.
 *
 * In the finished mod most entries will arrive from per-world data files rather than from
 * [defaults]; this class is the in-memory shape that both the data loader and the simulation
 * agree on. It deliberately has no Minecraft dependency so it can be unit-tested directly.
 */
class SpeciesRegistry {
    private val byId = LinkedHashMap<String, Species>()
    private val byFormula = HashMap<Formula, MutableList<Species>>()

    fun register(species: Species): Species {
        require(!byId.containsKey(species.id)) { "Species already registered: ${species.id}" }
        byId[species.id] = species
        byFormula.getOrPut(species.formula) { ArrayList() }.add(species)
        return species
    }

    fun get(id: String): Species? = byId[id]

    fun require(id: String): Species =
        byId[id] ?: throw NoSuchElementException("Unknown species: '$id'")

    fun contains(id: String): Boolean = byId.containsKey(id)

    val all: Collection<Species> get() = byId.values

    val ids: Set<String> get() = byId.keys

    /** Every species with the given formula, optionally restricted to one [phase]. */
    fun byFormula(formula: Formula, phase: Phase? = null): List<Species> =
        (byFormula[formula] ?: emptyList()).filter { phase == null || it.phase == phase }

    companion object {
        const val O2 = "aetheris:oxygen"
        const val N2 = "aetheris:nitrogen"
        const val H2 = "aetheris:hydrogen"
        const val CO = "aetheris:carbon_monoxide"
        const val CO2 = "aetheris:carbon_dioxide"
        const val H2O_GAS = "aetheris:water_vapor"
        const val H2O_LIQUID = "aetheris:water"
        const val CH4 = "aetheris:methane"

        /**
         * A small, curated starting catalogue: air, the common combustion products, the
         * light hydrocarbons, and a few solids used by later reaction templates. Not meant
         * to be exhaustive - it exists so the engine has something real to chew on.
         *
         * Formation enthalpies and cp values are standard literature values (J/mol, J/(mol*K)).
         */
        fun defaults(): SpeciesRegistry {
            val r = SpeciesRegistry()
            // Elements and air.
            r.register(Species(O2, "Oxygen", Formula.parse("O2"), Phase.GAS, 0.0, 29.38))
            r.register(Species(N2, "Nitrogen", Formula.parse("N2"), Phase.GAS, 0.0, 29.12))
            r.register(Species(H2, "Hydrogen", Formula.parse("H2"), Phase.GAS, 0.0, 28.84))
            r.register(Species("aetheris:helium", "Helium", Formula.parse("He"), Phase.GAS, 0.0, 20.79))
            r.register(Species("aetheris:argon", "Argon", Formula.parse("Ar"), Phase.GAS, 0.0, 20.79))
            r.register(Species("aetheris:chlorine", "Chlorine", Formula.parse("Cl2"), Phase.GAS, 0.0, 33.95))

            // Carbon oxides.
            r.register(Species(CO, "Carbon monoxide", Formula.parse("CO"), Phase.GAS, -110_530.0, 29.14))
            r.register(Species(CO2, "Carbon dioxide", Formula.parse("CO2"), Phase.GAS, -393_510.0, 37.13))

            // Water, both phases.
            r.register(Species(H2O_GAS, "Water vapor", Formula.parse("H2O"), Phase.GAS, -241_820.0, 33.58))
            // Liquid water is the solvent for aqueous solutes, so both share the "water" group.
            r.register(
                Species(
                    H2O_LIQUID, "Water", Formula.parse("H2O"), Phase.LIQUID, -285_830.0, 75.30,
                    solventGroup = "water",
                )
            )

            // Hydrocarbons and oxygenates (combustion fuel candidates).
            r.register(Species(CH4, "Methane", Formula.parse("CH4"), Phase.GAS, -74_810.0, 35.69))
            r.register(Species("aetheris:ethane", "Ethane", Formula.parse("C2H6"), Phase.GAS, -83_820.0, 52.49))
            r.register(Species("aetheris:propane", "Propane", Formula.parse("C3H8"), Phase.GAS, -103_850.0, 73.60))
            r.register(Species("aetheris:butane", "Butane", Formula.parse("C4H10"), Phase.GAS, -126_150.0, 97.45))
            r.register(Species("aetheris:methanol", "Methanol", Formula.parse("CH4O"), Phase.LIQUID, -238_400.0, 81.60))
            r.register(Species("aetheris:ethanol", "Ethanol", Formula.parse("C2H6O"), Phase.LIQUID, -277_690.0, 112.30))
            r.register(Species("aetheris:benzene", "Benzene", Formula.parse("C6H6"), Phase.LIQUID, 49_000.0, 136.10))

            // Nitrogen/sulfur gases.
            r.register(Species("aetheris:ammonia", "Ammonia", Formula.parse("NH3"), Phase.GAS, -45_940.0, 35.06))
            r.register(Species("aetheris:nitric_oxide", "Nitric oxide", Formula.parse("NO"), Phase.GAS, 90_250.0, 29.84))
            r.register(Species("aetheris:nitrogen_dioxide", "Nitrogen dioxide", Formula.parse("NO2"), Phase.GAS, 33_180.0, 37.20))
            r.register(Species("aetheris:hydrogen_sulfide", "Hydrogen sulfide", Formula.parse("H2S"), Phase.GAS, -20_600.0, 34.19))
            r.register(Species("aetheris:sulfur_dioxide", "Sulfur dioxide", Formula.parse("SO2"), Phase.GAS, -296_810.0, 39.87))
            r.register(Species("aetheris:hydrogen_chloride", "Hydrogen chloride", Formula.parse("HCl"), Phase.GAS, -92_310.0, 29.12))

            // Condensed solids for later (mineral) reactions.
            r.register(Species("aetheris:graphite", "Graphite", Formula.parse("C"), Phase.SOLID, 0.0, 8.53))
            r.register(Species("aetheris:diamond", "Diamond", Formula.parse("C"), Phase.SOLID, 1_895.0, 6.11))
            r.register(Species("aetheris:iron", "Iron", Formula.parse("Fe"), Phase.SOLID, 0.0, 25.10))
            r.register(Species("aetheris:iron_oxide", "Iron(III) oxide", Formula.parse("Fe2O3"), Phase.SOLID, -824_200.0, 103.90))
            r.register(Species("aetheris:calcium_carbonate", "Calcium carbonate", Formula.parse("CaCO3"), Phase.SOLID, -1_206_920.0, 81.88))
            r.register(Species("aetheris:calcium_oxide", "Calcium oxide", Formula.parse("CaO"), Phase.SOLID, -635_090.0, 38.10))
            r.register(Species("aetheris:calcium_hydroxide", "Calcium hydroxide", Formula.parse("Ca(OH)2"), Phase.SOLID, -986_090.0, 87.49))
            r.register(Species("aetheris:silicon_dioxide", "Silicon dioxide", Formula.parse("SiO2"), Phase.SOLID, -910_700.0, 44.60))

            return r
        }
    }
}
