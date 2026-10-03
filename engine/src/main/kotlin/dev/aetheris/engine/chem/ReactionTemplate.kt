package dev.aetheris.engine.chem

/**
 * A rule that expands into concrete [Reaction]s against a registry.
 *
 * This is Aetheris's answer to "we are not Mendeleev": instead of hand-writing every
 * reaction, we encode a *pattern* (complete combustion, acid-base neutralisation, ...) once
 * and let it instantiate itself for every species that matches. One line of code covers a
 * whole family of molecules, and a new species added by a data pack automatically picks up
 * every applicable reaction.
 */
interface ReactionTemplate {
    val id: String

    /** All reactions this template can build from the species in [registry]. */
    fun instantiate(registry: SpeciesRegistry): List<Reaction>
}

/**
 * Complete combustion of any C/H/O species:
 *
 *     CxHyOz + (x + y/4 - z/2) O2 -> x CO2 + (y/2) H2O
 *
 * Coefficients are left fractional (an extent formulation tolerates halves) so the rate-law
 * order stays close to the physical one rather than ballooning. The same rule covers
 * hydrocarbons (`CH4`, `C2H6`), oxygenates (`CH3OH`, `C2H5OH`), hydrogen (`H2`) and carbon
 * monoxide (`CO`), because those are just the cases where x or y is zero.
 *
 * [preExponential] and [activationEnergy] are illustrative default Arrhenius parameters, not
 * literature values; they are meant to be replaced by real kinetic data per reaction.
 */
class CombustionTemplate(
    override val id: String = "aetheris:complete_combustion",
    private val oxygenId: String? = null,
    private val carbonDioxideId: String? = null,
    private val waterId: String? = null,
    private val activationEnergy: Double = 125_000.0,
    private val preExponential: Double = 1.0e6,
    private val phases: Set<Phase> = setOf(Phase.GAS, Phase.LIQUID),
) : ReactionTemplate {

    override fun instantiate(registry: SpeciesRegistry): List<Reaction> {
        val oxygenSpecies = resolveSpecies(registry, oxygenId, "O2")
        val carbonDioxideSpecies = resolveSpecies(registry, carbonDioxideId, "CO2")
        val waterSpecies = resolveSpecies(registry, waterId, "H2O")

        val reactions = ArrayList<Reaction>()
        for (species in registry.all) {
            if (species.phase !in phases) continue
            if (species.formula.charge != 0) continue
            if (!species.formula.only("C", "H", "O")) continue

            val carbon = species.formula.countOf("C")
            val hydrogen = species.formula.countOf("H")
            val oxygen = species.formula.countOf("O")
            if (carbon == 0 && hydrogen == 0) continue

            val oxygenCoefficient = carbon + hydrogen / 4.0 - oxygen / 2.0
            if (oxygenCoefficient <= 0.0) continue

            val reactants = linkedMapOf(oxygenSpecies to oxygenCoefficient, species.id to 1.0)
            val products = LinkedHashMap<String, Double>()
            if (carbon > 0) products[carbonDioxideSpecies] = carbon.toDouble()
            if (hydrogen > 0) products[waterSpecies] = hydrogen / 2.0

            reactions += Reaction(
                id = "$id/${species.id}",
                reactants = reactants,
                products = products,
                activationEnergy = activationEnergy,
                preExponential = preExponential,
                name = "Complete combustion of ${species.name}",
            )
        }
        return reactions
    }

    /**
     * Resolves a reaction participant either by an explicit id or, when none was given, by
     * formula and gas phase.
     *
     * Resolving by formula is what lets one template run unchanged over any catalogue: the
     * hand-curated defaults name oxygen `aetheris:oxygen`, while the NASA import names it
     * `aetheris:o2`, and the pattern should not care.
     */
    private fun resolveSpecies(registry: SpeciesRegistry, explicitId: String?, formula: String): String {
        if (explicitId != null) {
            require(registry.contains(explicitId)) { "Combustion requires species '$explicitId'" }
            return explicitId
        }
        return registry.byFormula(Formula.parse(formula), Phase.GAS).firstOrNull()?.id
            ?: throw IllegalArgumentException("Combustion requires a gaseous '$formula' species")
    }
}

/**
 * Arrhenius parameters shipped for precipitation and dissolution.
 *
 * These are **not** tuned for watchability, which is what the earlier pair was and what made a lump of chalk
 * take days to dissolve. They are set from the one thing about this chemistry that is observable in the world:
 * a sparingly soluble salt reaches saturation from a solid in minutes to hours, which is what "sparingly
 * soluble" describes. Calcite's K is about 2e8, so a forward rate constant of 4e6 per second puts the
 * dissolution of a saturated litre on about an hour.
 *
 * Precipitation is the same elementary step running the other way, and is therefore fast: whenever a solution
 * is far from saturation the extent is clamped by the limiting reagent inside a single engine step. That is
 * also what it looks like in the world - mix two clear solutions and the solid appears at once - so the speed is
 * a consequence of the same number, not a second decision.
 *
 * 4e6 L/(mol*s) is a plausible surface-reaction rate constant, three orders of magnitude below the diffusion
 * limit, so there is no fudge factor standing in for physics here.
 *
 * The **equilibrium is untouched** by any of it. `kReverse = kForward / K` is detailed balance rather than a
 * modelling choice, so the solubility product stays exactly what the formation energies say, and retuning these
 * numbers changes only how long it takes to arrive. `SolubilityTest`, `PrecipitationKineticsTest` and
 * `KineticsProbeTest` pin that division of labour: one the ceiling, the others the road to it.
 */
private const val PRECIPITATION_ACTIVATION_ENERGY = 10_000.0
private const val PRECIPITATION_PRE_EXPONENTIAL = 2.25e8

/**
 * Acid attacking a metal, as a reversible-free family:
 *
 *     M(s) + n H+ -> M^n+ + (n/2) H2(g)
 *
 * The metals are **authored, not derived**, and that is the whole point of the template. "Does this metal
 * dissolve in acid" is chemistry, not arithmetic: copper sits below hydrogen in the reactivity series and is
 * untouched by hydrochloric acid, so a rule generated from "every metal that has an aqueous ion" would
 * invent a reaction that does not happen. Adding zinc or aluminium later is one entry plus its ion species.
 *
 * Not reversible, because the hydrogen leaves as a gas: there is no reverse reaction to balance against, so
 * the acid and the metal are simply consumed until one of them runs out. That is what makes a dilute acid
 * stop after neutralising itself instead of eating a container through - see the note on the rate constants.
 *
 * The rate constants are calibrated against the *rate law* rather than guessed, and the numbers are worth
 * recording because the law is easy to misread: the driving term uses **dimensional** activities, so 1 M H+ is
 * an activity of 1000 mol/m^3 and its square is a million. With the reaction correctly sized by the solution
 * (see [Mixture.reactionVolume]) a litre of 1 M acid is **half spent in about two minutes**, nine tenths spent
 * by half an hour, and a 10 M litre eats through a 3.6 mol bucket in well under a minute. That spread is the
 * whole mechanic - dilute acid stains a container, concentrated acid destroys it - and `MetalAcidTest` pins the
 * *shape* of the curve, not merely its endpoint, because the first calibration asserted only that the acid was
 * eventually consumed and a rate a million times too fast passed that happily.
 *
 * The order follows the stoichiometry, as it does everywhere else here, so the law is second order in H+ and
 * has the long tail that implies. For acid attack that is the right direction: a bucket discolours noticeably
 * long before it is eaten through.
 */
class MetalAcidTemplate(
    override val id: String = "aetheris:metal_acid",
    private val metals: Map<String, Int>,
    private val acidFormula: String = "H^+",
    private val hydrogenFormula: String = "H2",
    private val activationEnergy: Double = 25_000.0,
    private val preExponential: Double = 0.1,
) : ReactionTemplate {

    override fun instantiate(registry: SpeciesRegistry): List<Reaction> {
        val acid = registry.byFormula(Formula.parse(acidFormula), Phase.AQUEOUS).firstOrNull()
            ?: throw IllegalArgumentException("Acid attack requires an aqueous '$acidFormula' species")
        val hydrogen = registry.byFormula(Formula.parse(hydrogenFormula), Phase.GAS).firstOrNull()
            ?: throw IllegalArgumentException("Acid attack requires a gaseous '$hydrogenFormula' species")

        val reactions = ArrayList<Reaction>()
        for ((metalId, charge) in metals) {
            require(charge > 0) { "Metal '$metalId' must have a positive ion charge (was $charge)" }
            val metal = registry.get(metalId) ?: continue
            // The ion is the same element with a charge, so it can be looked up rather than named: a data
            // pack that renames its ions still works.
            val ion = registry.byFormula(Formula.of(metal.formula.counts, charge), Phase.AQUEOUS)
                .firstOrNull() ?: continue

            reactions += Reaction(
                id = "$id/$metalId",
                reactants = linkedMapOf(metalId to 1.0, acid.id to charge.toDouble()),
                products = linkedMapOf(ion.id to 1.0, hydrogen.id to charge / 2.0),
                activationEnergy = activationEnergy,
                preExponential = preExponential,
                name = "Acid attack on ${metal.name}",
            )
        }
        return reactions
    }
}

/**
 * Precipitation and dissolution of sparingly soluble salts, as reversible reactions
 *
 *     sum(ions) <-> solid
 *
 * The point is that the solubility product is *not* authored: marking the reaction reversible
 * makes [ReactionEngine] derive `K = 1/Ksp` from the ions' and solid's formation enthalpies and
 * entropies, so the salt stops dissolving exactly where thermodynamics says it should.
 *
 * The ion -> solid pairing is authored, and now that [FormulaSplit] exists it could often be derived - a salt's
 * ions are the decomposition of its formula. It stays authored for the reason the rules always were: *which*
 * pairs actually form a solid is chemistry, not arithmetic, and a lookup that invented a precipitate out of any
 * two ions that happen to balance would be wrong far more often than it was right.
 *
 * **What is still missing here is nucleation.** Real precipitation from a *mildly* supersaturated solution is
 * slow, because forming the first crystal costs interfacial energy, and that barrier is what lets a solution sit
 * metastable for a while. Nothing in this engine models it, so with the rate constants set from the physical
 * timescale a solution only a few times past saturation comes out of solution as quickly as one thousands of
 * times past it. The direction that behaves correctly without a barrier is the other one - a lump of a
 * sparingly soluble solid dissolving slowly toward saturation - and that is the one the tests pin.
 */
class PrecipitationTemplate(
    override val id: String = "aetheris:precipitation",
    private val rules: List<Rule>,
) : ReactionTemplate {

    data class Rule(
        val solid: String,
        val ions: Map<String, Double>,
        val activationEnergy: Double = PRECIPITATION_ACTIVATION_ENERGY,
        val preExponential: Double = PRECIPITATION_PRE_EXPONENTIAL,
    )

    override fun instantiate(registry: SpeciesRegistry): List<Reaction> = rules.map { rule ->
        require(registry.contains(rule.solid)) { "Precipitation requires solid '${rule.solid}'" }
        for (ion in rule.ions.keys) {
            require(registry.contains(ion)) { "Precipitation rule for '${rule.solid}' requires ion '$ion'" }
        }
        Reaction(
            id = "$id/${rule.solid}",
            reactants = rule.ions,
            products = mapOf(rule.solid to 1.0),
            activationEnergy = rule.activationEnergy,
            preExponential = rule.preExponential,
            reversible = true,
            name = "Precipitation of ${registry.require(rule.solid).name}",
        )
    }
}
