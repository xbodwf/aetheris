package dev.aetheris.client

import dev.aetheris.content.SolutionBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockAndTintGetter

/**
 * The liquid renderer's brain: the tint a solution block shows is read live from its block
 * entity, which holds the whole [dev.aetheris.engine.chem.Mixture]. Because the tint is the
 * blended colour of everything currently dissolved (see `Mixture.solutionColor`), a block turns
 * from blue to green the moment a reaction consumes one ion and leaves another - the rendering
 * is a view of the NBT, not a per-block texture.
 *
 * Returns an opaque-packable RGB tint (0xFFFFFF = untinted) for a block colour provider.
 */
object SolutionTint {

    fun color(world: BlockAndTintGetter?, pos: BlockPos?, tintIndex: Int): Int {
        if (world == null || pos == null) return WHITE
        val entity = world.getBlockEntity(pos) as? SolutionBlockEntity ?: return WHITE
        val argb = entity.mixture.solutionColor() ?: return WHITE
        return argb and 0xFFFFFF
    }

    private const val WHITE = 0xFFFFFF
}
