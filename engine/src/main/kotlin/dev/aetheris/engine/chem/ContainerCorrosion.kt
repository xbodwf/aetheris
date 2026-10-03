package dev.aetheris.engine.chem

/**
 * A container whose own substance is a reagent.
 *
 * The model is deliberately the world's own: the container's material is put into the contents as a solid,
 * the ordinary reaction step runs, and whatever is left is what the container still has. A bucket is
 * therefore eaten by exactly the chemistry that would eat a lump of iron lying in the same acid - no
 * separate corrosion rule, no separate rate constant - and a new acid, a new container material or a new
 * temperature all work without touching this class.
 *
 * The consequence worth stating, because it is the interesting one: a dilute acid is *neutralised* rather
 * than being an endless solvent. One litre of 1 M acid carries one mole of protons, which can dissolve half a
 * mole of iron - 28 g, against a bucket's ~200 g. So a dilute acid discolours a bucket and stops, and only a
 * concentrated one has enough protons to eat through. Rupture is not scripted; it is what "the material
 * reached zero" means.
 */
object ContainerCorrosion {

    /**
     * What a container is made of, and how much of it there is.
     *
     * [initialMoles] is carried along rather than left to the caller, because damage is a *fraction of the
     * original*: an earlier version reported the fraction of whatever was passed in, so once the acid was
     * spent every further step reported zero damage and a bucket that had visibly corroded looked pristine.
     * Keeping the baseline in the value makes that impossible to get wrong.
     */
    data class Material(
        val speciesId: String,
        /** What is left, mol. */
        val moles: Double,
        /** What it started with, mol. Defaults to [moles], i.e. an untouched container. */
        val initialMoles: Double = moles,
    ) {
        init {
            require(initialMoles > 0.0) { "A container must be made of something (was $initialMoles)" }
            require(moles >= 0.0) { "Remaining material cannot be negative (was $moles)" }
        }

        /** Fraction eaten so far, 0..1: what a container's appearance follows. */
        val damage: Double get() = ((initialMoles - moles) / initialMoles).coerceIn(0.0, 1.0)

        /** Less of the same container. */
        fun withRemaining(left: Double): Material = copy(moles = left.coerceAtLeast(0.0))
    }

    /** What one step of contact did. */
    data class Outcome(val material: Material, val ruptured: Boolean) {
        val remaining: Double get() = material.moles
        val damage: Double get() = material.damage
    }

    /**
     * Runs one step of contact between [material] and [contents], modifying [contents] in place.
     *
     * The material goes into the mixture for the step and comes back out afterwards, so the caller's mixture
     * is left holding only what a chemist would call the contents. What the material *became* stays in, which
     * is the point - dissolved iron accumulates in the acid that dissolved it.
     */
    fun step(
        engine: ReactionEngine,
        contents: Mixture,
        material: Material,
        dt: Double,
    ): Outcome {
        require(dt > 0.0) { "dt must be positive (was $dt)" }
        if (material.moles <= RUPTURE_MOLES) return Outcome(material, ruptured = true)
        // A material the registry does not model cannot be a reagent, so nothing happens to it. That is the
        // honest reading rather than a special case for glass: glass is simply a substance this chemistry has
        // no reactions for, which is exactly why a test tube carries acid safely and a bucket does not.
        if (!contents.registry.contains(material.speciesId)) return Outcome(material, ruptured = false)

        contents.add(material.speciesId, material.moles)
        engine.step(contents, dt)
        val remaining = contents.amount(material.speciesId).coerceAtLeast(0.0)
        contents.set(material.speciesId, 0.0)

        return Outcome(
            material = material.withRemaining(remaining),
            ruptured = remaining <= RUPTURE_MOLES,
        )
    }

    /**
     * Below this the wall is gone rather than merely thin.
     *
     * Not zero, because a mixture that has been through a few thousand steps carries floating-point dust, and
     * a container that never *quite* reaches empty would be a container that never breaks.
     */
    const val RUPTURE_MOLES = 1e-6
}
