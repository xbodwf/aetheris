package dev.aetheris.command

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.aetheris.AetherisCommon
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.Species
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/**
 * Tab completion for species ids, which is not a nicety here: the catalogue is over seven hundred entries
 * imported from NASA, NIST and a curated aqueous slice, and nobody is going to type `aetheris:hydrogen_ion`
 * from memory.
 *
 * Two things make this more delicate than it looks.
 *
 * **A greedy argument is completed as a whole.** Brigadier replaces the text from the *start of the
 * argument* to the cursor, and for `solutes` that start is the beginning of the entire list. Suggesting
 * `aetheris:copper_2_ion=` alone would therefore delete everything typed before it, so the suggestions here
 * are the earlier entries plus the new one, rebuilt as one string.
 *
 * **Order is a feature.** Seven hundred alphabetical completions are useless. Species are offered in the
 * order a solution is actually assembled - dissolved ions first, then solids that might be stirred in, then
 * the liquids that could be the medium - so the likely answers are at the top instead of the ones beginning
 * with `a`.
 */
object SpeciesSuggestions {

    /** How a phase sorts in the solute list: what you dissolve before what you dissolve it in. */
    private fun rank(phase: Phase): Int = when (phase) {
        Phase.AQUEOUS -> 0
        Phase.SOLID -> 1
        Phase.LIQUID -> 2
        Phase.GAS -> 3
        else -> 4
    }

    /**
     * Suggestions for `<species>=<molarity> ...`, completing the entry under the cursor.
     *
     * Once the entry already contains `=` the user is typing a number, where there is nothing useful to
     * offer, so the list goes quiet rather than proposing ids that would overwrite the number.
     */
    fun solutes(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val prefix = remaining.substringBeforeLast(' ', missingDelimiterValue = "")
        val entry = remaining.substringAfterLast(' ')
        if (entry.contains('=')) return builder.buildFuture()
        // Brigadier replaces from the argument's start, so the earlier entries have to be re-sent - with the
        // separator between them, or the completion is glued onto the previous entry and eats the space.
        val lead = if (prefix.isEmpty()) "" else "$prefix "

        val registry = AetherisCommon.chemistry.registry
        val typed = entry.lowercase()
        registry.all
            .asSequence()
            .filter { it.id.lowercase().startsWith(typed) || bare(it).startsWith(typed) }
            .sortedWith(compareBy({ rank(it.phase) }, { it.id }))
            .forEach { species ->
                // The equals sign is included so the cursor lands where the concentration goes.
                builder.suggest("$lead${species.id}=", tooltip(species))
            }
        return builder.buildFuture()
    }

    /**
     * Suggestions for the medium of a solution: the liquids, each labelled with the group that decides what
     * it mixes with. That label is the answer to "why did my glycerol vanish into my water", so it belongs
     * where somebody is choosing one.
     */
    fun liquids(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val registry = AetherisCommon.chemistry.registry
        val typed = builder.remaining.lowercase()
        registry.all
            .asSequence()
            .filter { it.phase == Phase.LIQUID && it.id.lowercase().startsWith(typed) }
            .sortedBy { it.id }
            .forEach { species ->
                val group = species.solventGroup ?: species.id
                builder.suggest(
                    species.id,
                    Component.literal("${species.name} ${species.formula} - mixes with group '$group'"),
                )
            }
        return builder.buildFuture()
    }

    /** The id without its namespace, which is what a player is likely to type. */
    private fun bare(species: Species): String = species.id.substringAfter(':').lowercase()

    private fun tooltip(species: Species): Component = Component.literal(
        buildString {
            append(species.name)
            append(' ')
            append(species.formula.pretty())
            append(" - ")
            append(species.phase.name.lowercase())
        },
    )
}
