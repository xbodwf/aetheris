package dev.aetheris.content

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import kotlin.math.roundToInt

/**
 * The carrier block for a body of solution: a vanilla [LiquidBlock] whose block entity holds the
 * chemistry.
 *
 * Extending `LiquidBlock` rather than `Block` is what makes it a fluid rather than a wet cube. The
 * inherited behaviour is precisely the set a player notices:
 *
 *  - `getShape`/`getCollisionShape` return an empty shape, so it never enters the targeting ray and
 *    therefore cannot be pointed at or broken - the actual reason vanilla water is unbreakable;
 *  - `getRenderShape` is `INVISIBLE`, so no block model is drawn and the fluid renderer draws the
 *    surface instead;
 *  - `getFluidState` maps the `LEVEL` property onto a fluid state, which is how the surface height and
 *    the still-versus-flowing texture follow how full the cell is;
 *  - `getDrops` is empty, and pickup goes through `getBucket` - see [AetherisFluid.getBucket], which
 *    answers with air until the bucket item exists.
 *
 * What it does *not* inherit is any notion of per-cell composition, because vanilla fluids have none.
 * That lives in [SolutionBlockEntity], and carrying it during transport is the entity's job.
 */
class SolutionBlock(fluid: FlowingFluid, properties: BlockBehaviour.Properties) :
    LiquidBlock(fluid, properties), EntityBlock {

    /**
     * Vanilla schedules a fluid's own spreading through random ticks. Ours is off.
     *
     * This is only half of the story - the scheduled-tick path is silenced in [AetherisFluid.tick], and
     * that one matters more, because a partly filled cell is a *non-source* fluid state and vanilla's
     * scheduled tick deletes those. Both paths have to go: vanilla's spread builds its destination from
     * `createLegacyBlock`, which carries no block entity, so a solution spreading under vanilla's rules
     * would arrive as a trail of empty white cells. Its pace also comes from `getTickDelay`, a property
     * of the fluid *type*, which has no room for a viscosity that depends on what is dissolved in
     * *this* cell. The entity spreads instead, carrying the mixture and pacing itself from that
     * mixture's own viscosity.
     */
    protected override fun isRandomlyTicking(state: BlockState): Boolean = false

    /*
     * NOT overridden here: `pickupBlock`.
     *
     * Scooping a solution into a bucket needs `pickupBlock` to put the mixture on the stack, and that
     * cannot be written from Kotlin while this class extends `LiquidBlock`. The evidence, so nobody has to
     * rediscover it:
     *
     *  - `javap` on the compile jar shows `LiquidBlock.pickupBlock(Player, LevelAccessor, BlockPos,
     *    BlockState) -> ItemStack`: public, not final, class not final;
     *  - declaring it with `override` reports "overrides nothing";
     *  - adding `BucketPickup` to the supertypes to make it visible reports "accidental override", and the
     *    diagnostic shows Kotlin generating a bridge whose parameters are the inherited ones.
     *
     * The cause is that `LiquidBlock implements BucketPickup`, so the same JVM signature arrives from both
     * a superclass and an interface and Kotlin will not accept a single declaration for it. The fix is to
     * stop extending `LiquidBlock` and implement the block directly - `Block` plus `BucketPickup` plus
     * `EntityBlock` - mirroring the handful of behaviours actually relied on, all of which are already
     * read out of `LiquidBlock`'s bytecode: the LEVEL property, the nine-entry fluid-state cache
     * (`getSource(false)`, then `getFlowing(8 - i, false)` for i in 1..7, then `getFlowing(8, true)`),
     * the empty shape, and `RenderShape.INVISIBLE`. Until then the bucket can be placed but not filled.
     */

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

    companion object {
        /** Vanilla's fluid amount at a brim-full cell, matching [AetherisFluid.MAX_AMOUNT]. */
        const val MAX_AMOUNT = AetherisFluid.MAX_AMOUNT

        /**
         * The block level that renders [fillFraction] of a cell as liquid.
         *
         * Vanilla counts the opposite way round from intuition: block level 0 *is* the full source
         * state and larger numbers are emptier, level 8 being a falling block. `LiquidBlock` builds its
         * level-to-fluid-state table on exactly that convention, so a brim-full cell has to be 0 and a
         * thin film 7. The property itself spans 0..15, but levels above 8 all clamp to the falling
         * state, so this never returns one: nothing here falls - the entity's flow moves matter
         * downwards by filling the cell below.
         */
        fun levelFor(fillFraction: Double): Int {
            val amount = (fillFraction.coerceIn(0.0, 1.0) * MAX_AMOUNT).roundToInt()
            return (MAX_AMOUNT - amount).coerceIn(0, MAX_AMOUNT - 1)
        }
    }
}
