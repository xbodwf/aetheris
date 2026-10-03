package dev.aetheris.content

import dev.aetheris.AetherisCommon
import dev.aetheris.engine.chem.ContainerCorrosion
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.WetChemistry
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * A bucket of solution: the vanilla way to move a fluid, carrying the mixture with it - and, because it is
 * made of iron, the one container that acid gets to argue with.
 *
 * It is a real [BucketItem] over the real fluid, so the pickup half is vanilla's own - right-clicking a
 * solution with an empty bucket goes through `BucketPickup`, which is where the contents get written onto the
 * stack. Only the *placing* half is overridden, because that half has to hand the block entity its mixture
 * back and `BucketItem.emptyContents` is never given the stack to read it from.
 *
 * The corrosion here is deliberately thin. All the chemistry lives in [ContainerCorrosion], which runs the
 * ordinary reaction engine with the bucket's own iron as a reactant; this class only decides *when* to ask
 * and what to do when the answer is "there is no wall left".
 */
class SolutionBucketItem(content: Fluid, properties: Item.Properties) : BucketItem(content, properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        // Fluid-aware picking, so that aiming at the liquid works despite the block having no shape. Vanilla
        // aims at fluids with SOURCE_ONLY, which by design never finds these cells - see
        // [AetherisFluid.Still] - so this has to ask for any fluid rather than any source.
        val hit = player.pick(player.blockInteractionRange(), 1.0f, true)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack)
        val blockHit = hit as? BlockHitResult ?: return InteractionResultHolder.pass(stack)
        if (!level.mayInteract(player, blockHit.blockPos)) return InteractionResultHolder.pass(stack)

        val stored = MixtureTag.storedOn(stack)
        return if (stored == null) {
            scoop(level, stack, blockHit)
        } else {
            place(level, player, stack, stored, blockHit)
        }
    }

    /**
     * Fills an empty bucket from a brim-full cell: the half that vanilla would do through `BucketPickup`.
     *
     * That route is unavailable - `LiquidBlock.pickupBlock` cannot be overridden from Kotlin, and the note on
     * [SolutionBlock] records the evidence - so this does it instead. It is not a downgrade: this is the only
     * version of the operation that can put the mixture on the stack, which is the entire point of bucketing
     * a solution rather than a fluid.
     *
     * Brim-full means our own `LEVEL == 0`. Vanilla applies the same restriction to water - a flowing trickle
     * cannot be bucketed - and the reason here is arithmetic as well as physical: a bucket is exactly one
     * cell, so a partly filled cell would have to be carried at a partial volume and poured back as something
     * that is no longer a cell.
     */
    private fun scoop(level: Level, stack: ItemStack, hit: BlockHitResult): InteractionResultHolder<ItemStack> {
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.block !== AetherisContent.SOLUTION.get()) return InteractionResultHolder.pass(stack)
        if (state.getValue(LiquidBlock.LEVEL) != 0) return InteractionResultHolder.pass(stack)
        val entity = level.getBlockEntity(pos) as? SolutionBlockEntity
            ?: return InteractionResultHolder.pass(stack)

        if (!level.isClientSide) {
            // Taking the sample is what empties the cell, and the two have to happen together: a cell that
            // still holds matter must keep it, and one that holds none would render as a white puddle that is
            // not a solution of anything, so it is removed rather than left behind.
            val sample = entity.mixture.takeSample(FluidUnits.CELL_VOLUME)
                ?: return InteractionResultHolder.pass(stack)
            MixtureTag.store(stack, sample)
            ContainerTag.setMaterial(stack, IRON_BUCKET)
            ContainerTag.setTicks(stack, 0)
            if (entity.mixture.condensedVolume() <= 0.0) {
                level.removeBlock(pos, false)
            } else {
                entity.setChanged()
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    /**
     * Pours the bucket out as a cell.
     *
     * The container handed back is this mod's own **empty** bucket rather than vanilla's, so that the same
     * item can be filled again by [scoop]. Without that the loop would be broken one step after it started:
     * pouring out would leave the player holding a vanilla bucket, which cannot pick a solution up.
     */
    private fun place(
        level: Level,
        player: Player,
        stack: ItemStack,
        stored: CompoundTag,
        hit: BlockHitResult,
    ): InteractionResultHolder<ItemStack> {
        val target = placement(level, hit.blockPos, hit.direction)
            ?: return InteractionResultHolder.pass(stack)

        if (!level.isClientSide) {
            val state = AetherisContent.SOLUTION.get().defaultBlockState()
                .setValue(LiquidBlock.LEVEL, 0)
            level.setBlock(target, state, Block.UPDATE_ALL)
            val entity = level.getBlockEntity(target) as? SolutionBlockEntity
            if (entity != null) {
                entity.mixture.volume = FluidUnits.CELL_VOLUME
                MixtureTag.read(stored, entity.mixture)
                entity.setChanged()
                level.sendBlockUpdated(target, state, state, Block.UPDATE_CLIENTS)
            }
        }

        // The solution is spent **even in creative**, which is a deliberate departure from vanilla's bucket.
        //
        // Vanilla lets a creative bucket place water forever because water is uniform and free. A solution is
        // neither: a bucket that kept its contents would be a tap that can pour any chemistry in the catalogue
        // into the world without limit, and every careful thing the engine does about conservation would stop
        // meaning anything. So the vessel survives, as creative items do, and its contents do not.
        MixtureTag.clearContents(stack)
        if (!player.abilities.instabuild) stack.shrink(1)
        val emptied = if (player.abilities.instabuild) stack else ItemStack(AetherisContent.SOLUTION_BUCKET.get())
        return InteractionResultHolder.sidedSuccess(emptied, level.isClientSide)
    }

    /**
     * Where the solution would go: the targeted block if it is open, otherwise the face in front of it.
     *
     * A solution cell is deliberately **not** open here. It is replaceable in general - you can put a block
     * into a puddle - but pouring a bucket onto one would silently delete a reacting mixture, and a bucket is
     * not a measuring instrument anyway. Two bodies of solution that should mix can simply be placed side by
     * side: adjacent cells level themselves out on their own.
     */
    private fun placement(level: Level, pos: BlockPos, direction: Direction): BlockPos? {
        if (isOpen(level.getBlockState(pos))) return pos
        val front = pos.relative(direction)
        return if (isOpen(level.getBlockState(front))) front else null
    }

    private fun isOpen(state: BlockState): Boolean =
        state.block !== AetherisContent.SOLUTION.get() && state.canBeReplaced()

    /**
     * What the bucket is carrying, and how much of the bucket is left.
     *
     * A bucket is held rather than looked at, so the goggle readout cannot help with it: the tooltip is the
     * only place these numbers can be read. Without the damage line the corrosion would be visible only as a
     * colour, with no way to tell a bucket that is nearly through from one that has merely been stained.
     */
    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val stored = MixtureTag.storedOn(stack)
        if (stored == null) {
            tooltip += Component.translatable("aetheris.readout.empty").withStyle(ChatFormatting.GRAY)
            return
        }
        val contents = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
        MixtureTag.read(stored, contents)
        tooltip += SolutionReadoutLines.of(contents)

        val damage = ContainerTag.damageOf(stack)
        if (damage > RUST_VISIBLE_FROM) {
            tooltip += Component.translatable("aetheris.bucket.rust", "%.0f".format(damage * 100.0))
                .withStyle(ChatFormatting.GOLD)
        }
    }

    /**
     * Lets the acid work on the bucket, once a second.
     *
     * Server side only: the client's copy of the stack is whatever the server last sent it, so running the
     * chemistry in both places would have them disagree. The sync after a change is what makes the rust ramp
     * on the item's colour visible, because a stack whose components changed in an inventory is not pushed to
     * its owner on its own.
     */
    override fun inventoryTick(
        stack: ItemStack,
        level: Level,
        entity: Entity,
        slotId: Int,
        isSelected: Boolean,
    ) {
        if (level.isClientSide) return
        val stored = MixtureTag.storedOn(stack) ?: return
        val contents = Mixture(AetherisCommon.chemistry.registry, FluidUnits.CELL_VOLUME)
        MixtureTag.read(stored, contents)
        if (contents.condensedVolume() <= 0.0) return

        val ticks = ContainerTag.ticksOf(stack) + 1
        if (ticks < CORROSION_INTERVAL_TICKS) {
            ContainerTag.setTicks(stack, ticks)
            return
        }
        ContainerTag.setTicks(stack, 0)

        val material = ContainerTag.materialOf(stack) ?: IRON_BUCKET
        val damageBefore = material.damage
        val outcome = ContainerCorrosion.step(
            AetherisCommon.chemistry,
            contents,
            material,
            CORROSION_INTERVAL_TICKS / 20.0,
        )
        // The dissolved iron stays in the liquid: an iron bucket of acid ends up holding iron(II) ions, which
        // is the visible half of what just happened to the bucket.
        //
        // The hydrogen does not stay. A bucket is open, so the gas the corrosion just made bubbles straight
        // out instead of being reported as a solute - half a mole in a litre would be some six hundred times
        // what water can hold, and about twelve bar if it were real. See Mixture.ventGases.
        contents.ventGases()
        MixtureTag.store(stack, contents)
        ContainerTag.setMaterial(stack, outcome.material)

        if (outcome.ruptured && release(level, entity, contents)) {
            // There is no wall left, so the solution goes where the bucket is and the bucket does not survive.
            MixtureTag.clearFrom(stack)
            stack.shrink(1)
        }

        // Pushed only when the container's *appearance* moved by a visible step, not every second.
        //
        // Replacing the stack in the selected slot makes the game replay the item's equip animation, and since
        // corrosion rewrites the contents every step a carried bucket twitched in the hand once a second -
        // which an empty bucket never did, having nothing to rewrite. Rust is what the player can actually see
        // while holding it, so that is what gates the sync; the contents reach the tooltip a step behind.
        if (outcome.ruptured || outcome.damage - damageBefore >= SYNC_DAMAGE_STEP) {
            if (entity is Player) entity.inventoryMenu.broadcastChanges()
        }
    }

    /**
     * Puts the contents into the world around [entity], and reports whether it found anywhere to put them.
     *
     * Closest first - underfoot, then eye level, then the four sides, then below - because a burst bucket
     * should end up where the bucket was. If nowhere is open the answer is false and the caller keeps the
     * contents, so a rupture in a sealed space waits rather than deleting the mixture.
     */
    private fun release(level: Level, entity: Entity, contents: Mixture): Boolean {
        val origin = entity.blockPosition()
        val candidates = buildList {
            add(origin)
            add(origin.above())
            for (direction in Direction.Plane.HORIZONTAL) add(origin.relative(direction))
            add(origin.below())
        }

        for (candidate in candidates) {
            if (!isOpen(level.getBlockState(candidate))) continue
            val placed = AetherisContent.SOLUTION.get().defaultBlockState().setValue(LiquidBlock.LEVEL, 0)
            level.setBlock(candidate, placed, Block.UPDATE_ALL)
            val created = level.getBlockEntity(candidate) as? SolutionBlockEntity ?: continue
            created.mixture.volume = contents.volume
            created.mixture.temperature = contents.temperature
            created.mixture.replace(contents.snapshot())
            created.setChanged()
            level.sendBlockUpdated(candidate, placed, placed, Block.UPDATE_CLIENTS)
            return true
        }
        return false
    }

    private companion object {
        /** Corrosion is evaluated once a second, which is the engine's usual step. */
        const val CORROSION_INTERVAL_TICKS = 20

        /**
         * Below this the rust line is left off the tooltip.
         *
         * One percent of a bucket is a rounding error, and a tooltip that announces "0% corroded" on every
         * fresh bucket is noise.
         */
        const val RUST_VISIBLE_FROM = 0.005

        /**
         * How far corrosion has to move before the stack is pushed to its owner, as a fraction of the bucket.
         *
         * A hundredth is about the smallest change the rust ramp shows, so syncing more often than this buys
         * nothing visible and costs the item's equip animation every time.
         */
        const val SYNC_DAMAGE_STEP = 0.01

        /**
         * A bucket's worth of iron: about 200 g, or 3.6 mol, which is what a real one weighs.
         *
         * The number is the whole reason dilute acid stains a bucket and concentrated acid eats through it,
         * so it is a physical quantity rather than a hit-point total.
         */
        val IRON_BUCKET = ContainerCorrosion.Material(WetChemistry.IRON, 3.6)
    }
}
