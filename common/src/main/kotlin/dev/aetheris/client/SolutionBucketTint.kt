package dev.aetheris.client

import dev.aetheris.AetherisCommon
import dev.aetheris.content.ContainerTag
import dev.aetheris.content.MixtureTag
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/**
 * Colour for the solution bucket's two tinted layers.
 *
 * The bucket borrows vanilla's own art **by reference** - the item model points at `minecraft:item/bucket`
 * - so no Mojang asset is copied into this repository, the bucket looks exactly like every other bucket, and
 * a resource pack that retextures buckets retextures this one too. Everything this mod contributes is colour.
 *
 * An item model's tint index is its **layer number**, the same mechanism vanilla uses to dye leather armour,
 * and both layers here want a different thing:
 *
 *  - layer 0, vanilla's bucket: the **rust** ramp. Pristine is white, which tints nothing at all, and a
 *    container the acid has been at turns brown. There is no texture per stage, because corrosion is
 *    continuous and its appearance should be too.
 *  - layer 1, our own near-white liquid shape: the **solution's own colour**, read through the same
 *    `Mixture.solutionColor` that draws the liquid in the world. A bucket of copper sulfate is therefore the
 *    same blue as the puddle it was scooped from, and it changes as the mixture reacts. Transparent when the
 *    bucket holds nothing, and the item render type's alpha test discards it.
 */
object SolutionBucketTint {

    private const val BUCKET_LAYER = 0
    private const val CONTENTS_LAYER = 1

    /**
     * Opaque white, and the **opaque** part is not decoration.
     *
     * An item tint's alpha is used by the renderer - `ItemRenderer.renderModelLists` takes
     * `FastColor.ARGB32.alpha(colour)` and multiplies it into the vertex - so the 24-bit `0xFFFFFF` that is
     * correct for a *block* colour provider is fully transparent here, and an item tinted with it is drawn
     * as nothing at all. Block tints get their alpha from the quad and so are conventionally 24-bit; item
     * tints carry their own. `SolutionTint` is therefore right to return RGB, and this is right to return
     * ARGB, and the two must not be "unified".
     */
    private const val PRISTINE = 0xFFFFFFFF.toInt()

    /** What iron looks like once acid has taken some of it. */
    private const val RUST = 0xFF8A5A2B.toInt()

    /** Fully transparent white: the contents layer is discarded rather than drawn black. */
    private const val NOTHING = 0x00FFFFFF

    /** Forced into every visible colour, because a tint without it disappears. */
    private const val OPAQUE = 0xFF000000.toInt()

    fun color(stack: ItemStack, tintIndex: Int): Int = when (tintIndex) {
        CONTENTS_LAYER -> contents(stack)
        BUCKET_LAYER -> rust(ContainerTag.damageOf(stack))
        else -> PRISTINE
    }

    private fun rust(damage: Double): Int =
        if (damage <= 0.0) PRISTINE else blend(PRISTINE, RUST, damage.coerceAtMost(1.0))

    private fun contents(stack: ItemStack): Int {
        val tag = MixtureTag.storedOn(stack) ?: return NOTHING
        val mixture = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
        MixtureTag.read(tag, mixture)
        // A colourless solution shows water's own tint rather than nothing, so that a bucket of acid looks
        // like a bucket of liquid; only a bucket with no contents at all is invisible.
        val argb = mixture.solutionColor() ?: (OPAQUE or SolutionTint.CLEAR)
        return argb or OPAQUE
    }

    private fun blend(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        // All four channels, alpha included: blending only RGB would drop the opacity back to zero and make a
        // corroding bucket fade out of existence instead of turning brown.
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
