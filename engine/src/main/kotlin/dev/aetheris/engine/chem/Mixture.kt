package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import kotlin.math.roundToInt

/**
 * A closed, well-mixed volume of matter: the lumped (0-D) state of one gas cell, sealed room,
 * airship cabin or tank.
 *
 * The mixture intentionally carries no spatial information. Three-dimensional behaviour comes
 * from many of these cells exchanging moles and energy through faces; a sealed or moving
 * volume is exactly one instance. Keeping the unit of simulation this small is what lets the
 * same reaction code serve both the field and the compartment model.
 *
 * Matter inside the cell is partitioned into coexisting [PhaseState]s: one gas phase, one liquid
 * phase per miscibility group, and one pure phase per solid. Each phase carries its own
 * composition and its own volume, which is what makes the three states of matter behave
 * correctly — molarity is per litre of *solvent*, a pure solid has unit activity, and the gas
 * sees only the headspace left over by the condensed matter.
 *
 * [volume] is the total cell volume in m^3 and [temperature] in K. Amounts are in mol.
 */
class Mixture(
    val registry: SpeciesRegistry,
    var volume: Double,
    temperatureK: Double = Constants.T0,
    composition: Map<String, Double> = emptyMap(),
) {
    private val phases = LinkedHashMap<String, PhaseState>()

    var temperature: Double = temperatureK
        set(value) {
            require(value > 0.0) { "Temperature must be positive (was $value)" }
            field = value
        }

    init {
        require(volume > 0.0) { "Volume must be positive (was $volume)" }
        require(temperatureK > 0.0) { "Temperature must be positive (was $temperatureK)" }
        for ((id, moles) in composition) set(id, moles)
    }

    /** The coexisting phases, in insertion order (gas, then liquids, then solids). */
    val phaseStates: List<PhaseState> get() = phases.values.toList()

    private data class PhaseId(val kind: PhaseKind, val group: String) {
        val key: String get() = "$kind|$group"
    }

    private fun phaseIdOf(species: Species): PhaseId = when (species.phase) {
        Phase.GAS -> PhaseId(PhaseKind.GAS, "gas")
        Phase.AQUEOUS -> PhaseId(PhaseKind.LIQUID, species.solventGroup ?: "water")
        Phase.LIQUID -> PhaseId(PhaseKind.LIQUID, species.solventGroup ?: species.id)
        Phase.SOLID -> PhaseId(PhaseKind.SOLID, species.id)
    }

    private fun phaseOf(id: String): PhaseState? =
        phases[phaseIdOf(registry.require(id)).key]

    fun set(id: String, moles: Double) {
        // Tolerate floating-point dust from a clamped reverse extent, but reject real negatives.
        require(moles >= -1e-9) { "Amount of '$id' cannot be negative (was $moles)" }
        val value = if (moles <= 1e-12) 0.0 else moles
        val phaseId = phaseIdOf(registry.require(id))
        val existing = phases[phaseId.key]
        if (value == 0.0) {
            existing?.set(id, 0.0)
            if (existing != null && existing.isEmpty) phases.remove(phaseId.key)
            return
        }
        val phase = existing ?: PhaseState(phaseId.kind, phaseId.group).also { phases[phaseId.key] = it }
        phase.set(id, value)
    }

    fun add(id: String, delta: Double) = set(id, amount(id) + delta)

    /** Empties the cell of all matter (keeps volume and temperature); used when loading from NBT. */
    fun clear() = phases.clear()

    /** Sets many species at once, replacing any existing amounts. */
    fun replace(composition: Map<String, Double>) {
        clear()
        for ((id, moles) in composition) set(id, moles)
    }

    val speciesIds: Set<String>
        get() = phases.values.flatMapTo(LinkedHashSet()) { it.speciesIds }

    fun amount(id: String): Double = phases.values.sumOf { it.amount(id) }

    fun totalMoles(): Double = phases.values.sumOf { it.totalMoles() }

    fun snapshot(): Map<String, Double> {
        val all = LinkedHashMap<String, Double>()
        for (phase in phases.values) {
            for ((id, moles) in phase.snapshot()) all.merge(id, moles, Double::plus)
        }
        return all
    }

    // --- Phase volumes -------------------------------------------------------------------

    /** Volume occupied by one phase, m^3: the gas headspace, or the condensed matter's own volume. */
    fun phaseVolume(phase: PhaseState): Double =
        if (phase.kind == PhaseKind.GAS) gasVolume()
        else phase.speciesIds.sumOf { phase.amount(it) * registry.require(it).molarVolume }

    /** Total volume of all condensed (liquid + solid) matter, m^3. */
    fun condensedVolume(): Double =
        phases.values
            .filter { it.kind != PhaseKind.GAS }
            .sumOf { phase -> phase.speciesIds.sumOf { phase.amount(it) * registry.require(it).molarVolume } }

    /**
     * Free volume left to the gas phase, m^3: the cell volume minus whatever the condensed
     * matter occupies. A 1% floor keeps the headspace positive (and pressure finite) when the
     * cell is nominally filled with liquid.
     */
    fun gasVolume(): Double = maxOf(volume - condensedVolume(), volume * 0.01)

    // --- Composition accessors -----------------------------------------------------------

    /** Cellular mole fraction, 0..1, across all species in the cell. */
    fun moleFraction(id: String): Double {
        val total = totalMoles()
        return if (total <= 0.0) 0.0 else amount(id) / total
    }

    /** Concentration of a species, mol/m^3, within the phase that holds it. */
    fun concentration(id: String): Double {
        if (amount(id) <= 0.0) return 0.0
        val phase = phaseOf(id) ?: return 0.0
        return amount(id) / phaseVolume(phase)
    }

    /** Molarity in mol/L (the chemist's unit) within the species' own liquid phase; 0 otherwise. */
    fun molarity(id: String): Double {
        val species = registry.require(id)
        if (species.phase == Phase.GAS || species.phase == Phase.SOLID) return 0.0
        return concentration(id) / 1000.0
    }

    /**
     * Thermodynamic activity used by the rate law.
     *
     * Gases and dissolved species use their phase concentration (mol/m^3), so liquid kinetics
     * scale with molarity rather than with the whole cell. A pure solid has unit activity, the
     * thermodynamic convention: how much precipitate sits there does not change its reactivity.
     * A return of 0 halts the reaction, so this doubles as the "is the reactant present" test.
     */
    fun activity(id: String): Double {
        if (amount(id) <= 0.0) return 0.0
        return when (registry.require(id).phase) {
            Phase.GAS, Phase.LIQUID, Phase.AQUEOUS -> concentration(id)
            Phase.SOLID -> 1.0
        }
    }

    /**
     * Standard-state activity of a species, matching the convention behind
     * [Reaction.equilibriumConstant]: gases as p/P0, solutes as molarity in mol/L (c0 = 1 M),
     * pure solids as 1. These are the activities that make `Q = K` the saturation condition.
     */
    fun standardActivity(id: String): Double {
        if (amount(id) <= 0.0) return 0.0
        return when (registry.require(id).phase) {
            Phase.GAS -> partialPressure(id) / Constants.P0
            Phase.SOLID -> 1.0
            Phase.LIQUID, Phase.AQUEOUS -> molarity(id)
        }
    }

    /** Reaction quotient Q in standard-state activities; infinite if a reactant is absent. */
    fun reactionQuotient(reaction: Reaction): Double {
        var q = 1.0
        for ((id, nu) in reaction.products) q *= Math.pow(standardActivity(id), nu)
        for ((id, nu) in reaction.reactants) {
            val a = standardActivity(id)
            if (a <= 0.0) return Double.POSITIVE_INFINITY
            q /= Math.pow(a, nu)
        }
        return q
    }

    /** Partial pressure of a gas species, Pa; zero for condensed phases. */
    fun partialPressure(id: String): Double {
        if (registry.require(id).phase != Phase.GAS) return 0.0
        return amount(id) * Constants.R * temperature / gasVolume()
    }

    /** Total pressure from the ideal-gas contribution of all gaseous species, Pa. */
    fun totalPressure(): Double = speciesIds.sumOf { partialPressure(it) }

    // --- Mass and density (what buoyancy reads) ------------------------------------------

    /** Mass of one phase, kg. */
    fun phaseMass(phase: PhaseState): Double =
        phase.speciesIds.sumOf { phase.amount(it) * registry.require(it).molarMass * 1e-3 }

    /** Mean density of one phase, kg/m^3. For the gas phase this is the ideal-gas density. */
    fun phaseDensity(phase: PhaseState): Double = phaseMass(phase) / phaseVolume(phase)

    /** Total mass in the cell, kg. */
    fun mass(): Double = phases.values.sumOf { phaseMass(it) }

    /** Mass of the gas phase, kg. */
    fun gasMass(): Double =
        phases.values.filter { it.kind == PhaseKind.GAS }.sumOf { phaseMass(it) }

    /**
     * Density of the gas phase, kg/m^3. Because the gas occupies only its headspace this is the
     * ambient density an object in the cell would feel — the number buoyancy compares against.
     */
    fun gasDensity(): Double = gasMass() / gasVolume()

    /** Cell-average density, kg/m^3. */
    fun averageDensity(): Double = mass() / volume

    // --- Viscosity (what flow rate reads) ------------------------------------------------

    /**
     * Dynamic viscosity of this cell's liquid, Pa*s, or `null` when no liquid species declares one.
     *
     * All liquid phases are pooled into a single body. Strictly, immiscible layers each have their own
     * viscosity and it is the continuous phase that governs how the cell drains; deciding that needs
     * the layering to be modelled first, so for now the mixture rule sees one liquid. Ions and other
     * solutes without a tabulated viscosity are excluded rather than assumed, which leaves a salt
     * solution flowing like water - the Jones-Dole correction that would fix that is a later refinement.
     */
    fun liquidViscosity(): Double? {
        var liquidMoles = 0.0
        for (phase in phases.values) {
            if (phase.kind == PhaseKind.LIQUID) liquidMoles += phase.totalMoles()
        }
        if (liquidMoles <= 0.0) return null

        val components = ArrayList<Pair<Double, Double>>()
        for (phase in phases.values) {
            if (phase.kind != PhaseKind.LIQUID) continue
            for (id in phase.speciesIds) {
                val viscosity = registry.require(id).viscosityPaS
                if (!Viscosity.isUsable(viscosity)) continue
                components += (phase.amount(id) / liquidMoles) to viscosity
            }
        }
        return Viscosity.mixture(components)
    }

    /**
     * How fast this cell's liquid moves, as a multiple of water's rate.
     *
     * This is a *rate in spreading steps per second*, not a fraction, so it can exceed one for a thin
     * liquid and fall far below one for a thick one. Cells with no liquid, or with no viscosity data
     * at all, report water's rate: assuming a substance is as mobile as water is the least surprising
     * default, and it keeps every species that has not been characterised behaving as it did before.
     */
    fun flowScale(): Double = liquidViscosity()?.let { Viscosity.flowScale(it) } ?: 1.0

    // --- Colour (what a solution looks like) ---------------------------------------------

    /**
     * Blended ARGB colour of the liquid phases, or null if no dissolved species is coloured.
     *
     * Two effects combine. The *hue* is the amount-weighted blend of every coloured solute, so
     * when a blue ion precipitates out and a green one is left the hue follows automatically.
     * The *intensity* scales with total coloured molarity (a Beer-Lambert stand-in), so a trace
     * left after precipitation fades toward colourless instead of staying fully saturated - which
     * is why a solution visibly loses its colour as it reacts, not only when the last ion is gone.
     */
    fun solutionColor(): Int? {
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        var colouredMolarity = 0.0
        for (phase in phases.values) {
            if (phase.kind != PhaseKind.LIQUID) continue
            for (id in phase.speciesIds) {
                val color = registry.require(id).color
                if (color == 0) continue
                val opacity = ((color ushr 24) and 0xFF) / 255.0
                if (opacity <= 0.0) continue
                val moles = phase.amount(id)
                val w = moles * opacity
                red += ((color ushr 16) and 0xFF) * w
                green += ((color ushr 8) and 0xFF) * w
                blue += (color and 0xFF) * w
                weight += w
                colouredMolarity += moles * opacity / (phaseVolume(phase) * 1000.0)
            }
        }
        if (weight <= 0.0) return null
        val saturation = (colouredMolarity / FULL_TINT_MOLARITY).coerceIn(0.0, 1.0)
        val r = blendToWhite(red / weight, saturation)
        val g = blendToWhite(green / weight, saturation)
        val b = blendToWhite(blue / weight, saturation)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blendToWhite(channel: Double, saturation: Double): Int =
        (255.0 + (channel - 255.0) * saturation).roundToInt().coerceIn(0, 255)

    /**
     * The volume a reaction's kinetics act over: the solution if any participant is dissolved in one,
     * otherwise the gas headspace, otherwise the volume of whichever phase holds a participant, otherwise the
     * whole cell. This turns a rate per unit volume into a rate in mol/s without pretending a condensed
     * reaction fills the whole cell.
     *
     * **The solution comes first, and that ordering is load-bearing.** Iron dissolving in acid makes hydrogen
     * - a gaseous *product* - and an earlier version asked "is any participant a gas?" and answered by sizing
     * the rate against the headspace. In a brim-full cell that headspace is nearly nothing, while the driving
     * term is a real concentration of 1000 mol/m^3 squared, so the whole litre of acid was consumed in a
     * single step: a bucket of hydrochloric acid became iron(II) chloride instantly instead of over a minute.
     * A gaseous product does not move a reaction into the gas phase. The reaction happens at the metal's
     * surface, in the solution, and the hydrogen merely leaves - which is [reactionVolume]'s job to respect.
     *
     * Products are consulted as well as reactants, which is what lets a reverse reaction (starting from the
     * product side) size its rate by the solution it actually runs in rather than falling back to the cell.
     */
    fun reactionVolume(reaction: Reaction): Double {
        val participants = reaction.reactants.keys + reaction.products.keys

        // A participant in a liquid phase means this is a solution reaction, wherever the other participants
        // are: a dissolving solid, a bubbling product, or an ion.
        for (id in participants) {
            val phase = phaseOf(id) ?: continue
            if (phase.kind == PhaseKind.LIQUID) return phaseVolume(phase)
        }
        // An aqueous participant that is not present *yet* still makes this a solution reaction. A lump of
        // chalk dropped into water dissolves into the water that is there, not into the chalk, so the rate is
        // sized by the solvent rather than by the lump - without this, how fast a solid dissolves would depend
        // on how big the piece of it happened to be, which is not chemistry anybody would recognise.
        if (participants.any { registry.require(it).phase == Phase.AQUEOUS }) {
            val solvent = speciesIds.firstOrNull { registry.require(it).phase == Phase.LIQUID }
            val phase = solvent?.let { phaseOf(it) }
            if (phase != null) return phaseVolume(phase)
        }
        if (participants.any { registry.require(it).phase == Phase.GAS }) return gasVolume()
        for (id in participants) {
            val phase = phaseOf(id) ?: continue
            return phaseVolume(phase)
        }
        return gasVolume()
    }

    // --- Thermodynamics ------------------------------------------------------------------

    /** Sensible internal energy of the whole mixture at its current temperature, J. */
    fun internalEnergy(): Double =
        speciesIds.sumOf { amount(it) * registry.require(it).internalEnergy(temperature) }

    /** Heat capacity of the whole mixture at constant pressure, J/K. */
    fun heatCapacity(): Double =
        speciesIds.sumOf { amount(it) * registry.require(it).heatCapacity }

    /** Total moles of each element present, used by the conservation checks. */
    fun elementTotals(): Map<String, Double> {
        val totals = HashMap<String, Double>()
        for (id in speciesIds) {
            val moles = amount(id)
            for ((symbol, count) in registry.require(id).formula.counts) {
                totals.merge(symbol, moles * count, Double::plus)
            }
        }
        return totals
    }

    /** Net electric charge, in elementary charge units (mol * charge). */
    fun charge(): Double = speciesIds.sumOf { amount(it) * registry.require(it).formula.charge }

    fun copy(): Mixture = Mixture(registry, volume, temperature, snapshot())

    /**
     * Removes up to [sampleVolume] of condensed matter and returns it as a cell of its own, or `null` when
     * there is nothing to take.
     *
     * The sample takes the *same fraction of every species*, which is what makes it a sample rather than a
     * partial separation: its concentration is identical to the source's, and so is the source's
     * afterwards. Its cell volume is set to the matter it holds, so it reads as a full cell and can be
     * poured or carried without a shrink factor to remember. This is the maths a pipette or a test tube
     * needs, and it lives here so it can be tested without a game.
     */
    fun takeSample(sampleVolume: Double): Mixture? {
        require(sampleVolume > 0.0) { "Sample volume must be positive (was $sampleVolume)" }
        val available = condensedVolume()
        if (available <= 0.0) return null
        val fraction = minOf(1.0, sampleVolume / available)
        val sample = Mixture(registry, maxOf(fraction * available, 1e-9), temperature)
        for (id in speciesIds.toList()) {
            val taken = amount(id) * fraction
            if (taken <= 0.0) continue
            sample.add(id, taken)
            add(id, -taken)
        }
        return sample
    }

    /** Absorbs every species of [other] into this cell. Volume and temperature are left alone, so the
     * added matter dilutes this cell rather than resizing it. */
    fun absorb(other: Mixture) {
        for (id in other.speciesIds) add(id, other.amount(id))
    }

    /**
     * Removes every gas, returning what left.
     *
     * This is the **open-vessel rule**, and it is why a bucket of acid does not end up holding hydrogen: a
     * bucket is a bucket, not a pressure vessel, and a gas the atmosphere does not contain simply bubbles out.
     * Without it the iron dissolving in a litre of acid left half a mole of H2 sitting in the solution, which
     * the goggles and the tooltip then reported as a dissolved solute - some six hundred times what water can
     * actually hold, and about twelve bar if it were real.
     *
     * The general form of this is "hold each gas at its atmospheric partial pressure and vent the excess",
     * which needs the atmosphere's composition. The atmosphere model has a total pressure and no composition
     * yet, so this is the honest degenerate case: for a gas the air does not contain, the partial pressure to
     * hold it at is zero.
     *
     * Deliberately not called by [Mixture] itself. A mixture that is *meant* to be sealed - a gas-phase cell, a
     * future pressure vessel - has to be able to keep its gases, so the choice belongs to the caller: see the
     * solution cell and the bucket.
     */
    fun ventGases(): Map<String, Double> {
        val leaving = LinkedHashMap<String, Double>()
        for (id in speciesIds.toList()) {
            if (registry.require(id).phase != Phase.GAS) continue
            val amount = amount(id)
            if (amount <= 0.0) continue
            leaving[id] = amount
            set(id, 0.0)
        }
        return leaving
    }

    override fun toString(): String =
        "Mixture(V=${volume}m^3, T=${"%.1f".format(temperature)}K, phases=${phaseStates})"

    companion object {
        /** Coloured molarity (mol/L) at which a tint reaches full saturation: about 1 mol in 20 L. */
        const val FULL_TINT_MOLARITY = 0.05
    }
}
