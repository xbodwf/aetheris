package dev.aetheris.content

import dev.aetheris.AetherisCommon
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * The test tube: a measured sample you can carry, pour, and shake.
 *
 * It differs from [SolutionBucketItem] in the two ways that matter. Its capacity is a **quarter of a cell**
 * ([FluidUnits.TEST_TUBE_MILLIBUCKETS]), so it takes a sample rather than a whole cell and can drain a shallow
 * puddle that a bucket is too big for - including the thin fringe a spreading puddle always leaves behind. And it
 * can be **agitated**: shaking a tube is the real-world way to make a reluctant crystallisation happen, and the
 * engine models exactly that, because a surface is what [dev.aetheris.engine.chem.Nucleation]'s barrier is the
 * cost of not having.
 *
 * Nothing here is consumed in creative except the liquid itself, for the same reason the bucket works that way:
 * a sample that could be duplicated would make every conservation rule in the engine decorative.
 */
class TestTubeItem(properties: Item.Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val sample = sampleOn(stack)

        // Sneaking shakes the tube rather than pouring it, which is the one gesture here that has no analogue in
        // a bucket and so cannot be confused with one.
        if (player.isShiftKeyDown) return agitate(level, player, stack, sample)

        // Fluid-aware picking, so aiming at the liquid works despite the block having no shape.
        val hit = player.pick(player.blockInteractionRange(), 1.0f, true)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack)
        val blockHit = hit as? BlockHitResult ?: return InteractionResultHolder.pass(stack)
        if (!level.mayInteract(player, blockHit.blockPos)) return InteractionResultHolder.pass(stack)

        return if (sample == null) {
            fill(level, stack, blockHit)
        } else {
            pour(level, player, stack, sample, blockHit)
        }
    }

    /**
     * Takes a sample from a solution cell, up to the tube's capacity.
     *
     * Unlike the bucket, this does **not** insist on a brim-full cell. A quarter-litre tube can take what it can
     * hold from any cell at all, and the cell keeps the rest - which is what makes a tube the tool for clearing up
     * a puddle, where a bucket can only ever take whole cells and therefore leaves the fringe.
     */
    private fun fill(level: Level, stack: ItemStack, hit: BlockHitResult): InteractionResultHolder<ItemStack> {
        val pos = hit.blockPos
        if (level.getBlockState(pos).block !== AetherisContent.SOLUTION.get()) {
            return InteractionResultHolder.pass(stack)
        }
        val entity = level.getBlockEntity(pos) as? SolutionBlockEntity
            ?: return InteractionResultHolder.pass(stack)

        if (!level.isClientSide) {
            val taken = entity.mixture.takeSample(FluidUnits.cubicMetres(TEST_TUBE_MILLIBUCKETS.toDouble()))
                ?: return InteractionResultHolder.pass(stack)
            MixtureTag.store(stack, taken)
            // An emptied cell is removed rather than left as a white puddle that is a solution of nothing.
            if (entity.mixture.condensedVolume() <= 0.0) {
                level.removeBlock(pos, false)
            } else {
                entity.setChanged()
                entity.syncAppearanceIfChanged()
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    /**
     * Pours the sample into a solution cell, or onto the ground.
     *
     * Into a cell it **mixes**, which is the point of carrying a measured sample: adding one reagent to another
     * and watching what happens. Onto the ground it becomes a cell of its own, and because the sample's own
     * volume comes with it, a quarter-litre tube makes a quarter-full cell rather than a full one.
     */
    private fun pour(
        level: Level,
        player: Player,
        stack: ItemStack,
        sample: CompoundTag,
        hit: BlockHitResult,
    ): InteractionResultHolder<ItemStack> {
        val pos = hit.blockPos
        val existing = level.getBlockEntity(pos) as? SolutionBlockEntity

        if (!level.isClientSide) {
            if (existing != null && level.getBlockState(pos).block === AetherisContent.SOLUTION.get()) {
                val poured = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
                MixtureTag.read(sample, poured)
                existing.mixture.absorb(poured)
                existing.setChanged()
                existing.syncAppearanceIfChanged()
            } else {
                val target = placement(level, pos, hit.direction)
                    ?: return InteractionResultHolder.pass(stack)
                val state = AetherisContent.SOLUTION.get().defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 0)
                level.setBlock(target, state, Block.UPDATE_ALL)
                val created = level.getBlockEntity(target) as? SolutionBlockEntity
                    ?: return InteractionResultHolder.pass(stack)
                // A cell holds a cell's worth, so the sample's own volume has to be replaced by the cell's: what
                // it carries is the matter, and a quarter of it fills a quarter of a block.
                created.mixture.volume = SolutionBlockEntity.DEFAULT_VOLUME
                MixtureTag.read(sample, created.mixture)
                created.setChanged()
                level.sendBlockUpdated(target, state, state, Block.UPDATE_CLIENTS)
            }
            // The liquid is spent even in creative. A sample that could be poured forever would be a tap with
            // every chemistry in the catalogue behind it - see SolutionBucketItem for the same reasoning.
            MixtureTag.clearContents(stack)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    /**
     * Shakes the tube: the gesture that turns a metastable solution into a crystallising one.
     *
     * A supersaturated solution can sit indefinitely because making the first crystal costs interfacial energy -
     * see [dev.aetheris.engine.chem.Nucleation]. Agitation scratches it against its own container and against
     * nuclei already forming, which is a surface, which is the one thing the barrier cannot survive. So the engine
     * answers with a seed rather than with a rate change, and the difference shows: the solid that appears is the
     * one the chemistry would have made anyway, and if the solution was not supersaturated nothing appears at all.
     */
    private fun agitate(
        level: Level,
        player: Player,
        stack: ItemStack,
        sample: CompoundTag?,
    ): InteractionResultHolder<ItemStack> {
        if (sample == null) return InteractionResultHolder.pass(stack)
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true)

        val mixture = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
        MixtureTag.read(sample, mixture)
        val formed = AetherisCommon.chemistry.seed(mixture)
        MixtureTag.store(stack, mixture)

        if (formed.isEmpty()) {
            player.displayClientMessage(Component.translatable("aetheris.tube.agitate.nothing"), true)
        } else {
            val names = formed.joinToString(", ") { AetherisCommon.chemistry.registry.require(it).name }
            player.displayClientMessage(Component.translatable("aetheris.tube.agitate.formed", names), true)
        }
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    /** Where a poured-out sample lands: the targeted block if it is open, otherwise the face in front of it. */
    private fun placement(level: Level, pos: BlockPos, direction: Direction): BlockPos? {
        if (isOpen(level.getBlockState(pos))) return pos
        val front = pos.relative(direction)
        return if (isOpen(level.getBlockState(front))) front else null
    }

    private fun isOpen(state: BlockState): Boolean =
        state.block !== AetherisContent.SOLUTION.get() && state.canBeReplaced()

    /**
     * What the tube is carrying, in the same lines the goggles and the bucket's tooltip use.
     *
     * Agitation is worth mentioning here because it is the only way anybody would discover it: a device with an
     * undocumented gesture is a device nobody uses.
     */
    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val sample = sampleOn(stack)
        if (sample == null) {
            tooltip += Component.translatable("aetheris.readout.empty").withStyle(ChatFormatting.GRAY)
        } else {
            val mixture = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
            MixtureTag.read(sample, mixture)
            tooltip += SolutionReadoutLines.of(mixture)
        }
        tooltip += Component.translatable("aetheris.tube.capacity", TEST_TUBE_MILLIBUCKETS.toDouble())
            .withStyle(ChatFormatting.DARK_GRAY)
        tooltip += Component.translatable("aetheris.tube.shake").withStyle(ChatFormatting.DARK_GRAY)
    }

    private fun sampleOn(stack: ItemStack): CompoundTag? = MixtureTag.storedOn(stack)

    private companion object {
        /** A test tube holds a quarter of a cell: see [FluidUnits.TEST_TUBE_MILLIBUCKETS]. */
        const val TEST_TUBE_MILLIBUCKETS = FluidUnits.TEST_TUBE_MILLIBUCKETS
    }
}
