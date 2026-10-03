package dev.aetheris.content

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * The carrier block for a body of solution: a block whose block entity holds the chemistry.
 *
 * It behaves like a fluid (non-solid, see-through, no occlusion) but unlike a real fluid its
 * contents are arbitrary and per-block, so two adjacent solution blocks can hold different
 * mixtures and react when they mix.
 */
class SolutionBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SolutionBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return BlockEntityTicker { _, _, _, blockEntity ->
            (blockEntity as? SolutionBlockEntity)?.serverTick()
        }
    }
}
