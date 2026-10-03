package dev.aetheris.content

import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.SolutionNames
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * A mixture written as lines of text, for anything that has to show one to a person - the goggle readout and
 * a container's tooltip both do, and they must agree.
 *
 * Formatting only: which species count as contents and which is the medium is [SolutionNames]' decision, and
 * this does not second-guess it.
 *
 * Deliberately free of any client-only type, so the same lines can be built on either side.
 */
object SolutionReadoutLines {

    /** Beyond this a tooltip stops being readable and starts being a wall. */
    const val DEFAULT_LIMIT = 4

    fun of(mixture: Mixture, limit: Int = DEFAULT_LIMIT): List<Component> {
        val readings = SolutionNames.readout(mixture, limit)
        if (readings.isEmpty()) {
            return listOf(Component.translatable("aetheris.readout.empty").withStyle(ChatFormatting.GRAY))
        }
        return readings.map { reading ->
            // What is dissolved is quoted as a concentration; what has settled out is quoted in moles,
            // because a solid has no concentration to quote.
            val amount = when (reading.phase) {
                Phase.SOLID, Phase.GAS ->
                    Component.translatable("aetheris.readout.moles", "%.3g".format(reading.moles))

                else ->
                    Component.translatable("aetheris.readout.molarity", "%.3g".format(reading.molarity))
            }
            // Subscripts and superscripts rather than the machine-readable `Cu^2+`: at the sizes this is read
            // at, the caret form is genuinely hard to follow.
            var line: MutableComponent = Component.literal("${reading.prettyFormula} ").append(amount)
            // Colour each line with the species' own tint, so a blue ion reads as blue - the same colour the
            // liquid itself is drawn with.
            if (reading.color != 0) line = line.withColor(reading.color and 0xFFFFFF)
            line
        }
    }
}
