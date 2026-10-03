package dev.aetheris.client

import dev.aetheris.content.SolutionBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockAndTintGetter
import kotlin.math.roundToInt

/**
 * The liquid renderer's brain: the tint a solution block shows is read live from its block entity,
 * which holds the whole [dev.aetheris.engine.chem.Mixture]. Because the tint is the blended colour of
 * everything currently dissolved (see `Mixture.solutionColor`), a block turns from blue to green the
 * moment a reaction consumes one ion and leaves another - the rendering is a view of the NBT, not a
 * per-block texture.
 *
 * A block colour provider is only ever asked "what colour is *this* block", with no vertex or
 * position-within-the-block information, so the only smoothing available is to average each cell with
 * its neighbours. That is what [NEIGHBOUR_WEIGHT] does: it turns the hard seam between a blue cell and
 * a pale one into a gradient over the shared face. A colourless solution contributes white rather than
 * being skipped, otherwise a spreading puddle would keep a hard edge exactly where it thins out.
 *
 * Returns an opaque-packable RGB tint (0xFFFFFF = untinted) for a block colour provider.
 */
object SolutionTint {

    /** Weight of the block's own colour against each neighbour's; neighbours share the rest. */
    private const val CENTRE_WEIGHT = 2.0
    private const val NEIGHBOUR_WEIGHT = 1.0

    fun color(world: BlockAndTintGetter?, pos: BlockPos?, tintIndex: Int): Int {
        if (world == null || pos == null) return WHITE

        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0

        fun sample(at: BlockPos, sampleWeight: Double) {
            val entity = world.getBlockEntity(at) as? SolutionBlockEntity ?: return
            // A solution with nothing coloured in it still has a colour, and it is not white: vanilla's own
            // water is tinted a pale blue, and a white tint on a translucent texture reads as an opaque milky
            // block rather than as a liquid. So a colourless solution borrows water's tint, which is also the
            // honest answer - it *is* a colourless solution, and this is what those look like.
            val argb = entity.mixture.solutionColor() ?: CLEAR
            red += ((argb shr 16) and 0xFF) * sampleWeight
            green += ((argb shr 8) and 0xFF) * sampleWeight
            blue += (argb and 0xFF) * sampleWeight
            weight += sampleWeight
        }

        sample(pos, CENTRE_WEIGHT)
        for (direction in Direction.values()) sample(pos.relative(direction), NEIGHBOUR_WEIGHT)

        if (weight <= 0.0) return WHITE
        val r = (red / weight).roundToInt().coerceIn(0, 255)
        val g = (green / weight).roundToInt().coerceIn(0, 255)
        val b = (blue / weight).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * What a solution with nothing coloured dissolved in it looks like.
     *
     * Vanilla's water tint, borrowed deliberately: it is the colour a Minecraft player already reads as
     * "colourless liquid", and inventing a different one would only make solutions look unlike water for no
     * reason. Shared with [SolutionBucketTint] so a bucket of the same liquid matches the puddle.
     */
    const val CLEAR = 0x3F76E4

    /** Nothing to tint against: no world or no position, which only happens outside normal rendering. */
    private const val WHITE = 0xFFFFFF
}
