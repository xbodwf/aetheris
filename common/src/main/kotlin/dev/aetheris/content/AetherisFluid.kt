package dev.aetheris.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState

/**
 * A solution, expressed as a real Minecraft fluid.
 *
 * Two things here are worth reading before changing anything.
 *
 * **It is a fluid so the game will draw it as one.** Vanilla renders fluids through
 * `FluidRenderer`, not through a block model, which is why `LiquidBlock` reports
 * `RenderShape.INVISIBLE` and why the surface height comes from `getOwnHeight`. That is the whole
 * difference between "a cube that is tinted" and "a body of liquid".
 *
 * **Vanilla's own transport is switched off**, in two places because the game schedules fluid flow from
 * two: `SolutionBlock.isRandomlyTicking` stops the random-tick path, and [tick] stops the scheduled
 * one. Following vanilla would mean two things this mod cannot accept: `createLegacyBlock` builds a
 * plain block state with no block entity, so every cell a fluid spreads into would arrive with no
 * mixture in it, and `getTickDelay` is a property of the *fluid type* rather than of the cell, so a
 * solution's viscosity - which depends on what is dissolved in that particular cell - would have
 * nowhere to live. The block entity carries the mixture and does the spreading instead; the fluid
 * supplies identity, level and rendering.
 */
abstract class AetherisFluid : FlowingFluid() {

    override fun getFlowing(): Fluid = AetherisContent.SOLUTION_FLOWING

    override fun getSource(): Fluid = AetherisContent.SOLUTION_STILL

    /**
     * The bucket that carries this fluid.
     *
     * Returning the real item is what makes `LiquidBlock.pickupBlock` - and every other path that asks a
     * fluid what its container is - behave; the contents ride along because [SolutionBlock.pickupBlock]
     * writes them onto the stack.
     */
    override fun getBucket(): Item = AetherisContent.SOLUTION_BUCKET.get()

    /**
     * Never a source in the world-generation sense: a cell holds what was put in it and nothing
     * replenishes it. Without this a spilled solution would breed more of itself.
     */
    override fun canConvertToSource(level: Level): Boolean = false

    override fun beforeDestroyingBlock(level: LevelAccessor, pos: BlockPos, state: BlockState) = Unit

    /** Unused while vanilla's spread is off, but the algorithm still asks for a horizon. */
    override fun getSlopeFindDistance(level: LevelReader): Int = SLOPE_FIND_DISTANCE

    override fun getDropOff(level: LevelReader): Int = DROP_OFF

    /** Likewise unused, and deliberately not where viscosity goes: see the class comment. */
    override fun getTickDelay(level: LevelReader): Int = TICK_DELAY

    override fun getExplosionResistance(): Float = EXPLOSION_RESISTANCE

    /**
     * Vanilla's fluid tick is a no-op here, and that is the load-bearing part of this class.
     *
     * Vanilla drives fluid flow from two separate places, and switching off only one of them is a trap.
     * `isRandomlyTicking` covers the random-tick path; this covers the *scheduled* one. `LiquidBlock`
     * schedules a tick for any cell whose fluid state is not a source, and `FlowingFluid.tick` then
     * recomputes that cell's level from its neighbours - finding no adjacent source, it concludes the
     * liquid is unsupported and **deletes the block**. Since a partly filled cell here is exactly a
     * non-source state (that is how it gets a low surface), every cell a solution spread into was being
     * erased on the next tick: the liquid visibly flowed out and then vanished.
     *
     * For a source cell the same tick would call `spread`, which builds its destination from
     * `createLegacyBlock` - a block state with no block entity, so a trail of empty cells. Neither
     * behaviour is acceptable, and both come from this one entry point, so it is silenced outright.
     * Transport belongs to [SolutionBlockEntity], which carries the mixture and its viscosity.
     */
    override fun tick(level: Level, pos: BlockPos, state: FluidState) = Unit

    /**
     * The block that stands for a given fluid state, by vanilla's own mapping.
     *
     * `FlowingFluid.getLegacyLevel` is
     * `isSource ? 0 : (8 - min(amount, 8)) + (falling ? 8 : 0)`, reproduced here rather than called
     * because it is a protected static on the superclass. Note the two level properties involved are
     * *not* the same thing: the fluid state carries `FlowingFluid.LEVEL`, whose values are 1..8, while
     * the block carries `LiquidBlock.LEVEL` - `BlockStateProperties.LEVEL`, spanning 0..15, of which
     * only 0..8 mean anything to a fluid. Mixing them up is what a crash during mod initialisation
     * looks like.
     *
     * The `isSource` branch of that expression is gone rather than kept dead: no state of this fluid is a
     * source, for the reason set out on [Still].
     */
    override fun createLegacyBlock(state: FluidState): BlockState {
        val level = (MAX_AMOUNT - minOf(state.amount, MAX_AMOUNT)) +
            if (state.getValue(FlowingFluid.FALLING)) MAX_AMOUNT else 0
        return AetherisContent.SOLUTION.get().defaultBlockState().setValue(LiquidBlock.LEVEL, level)
    }

    override fun isSame(fluid: Fluid): Boolean =
        fluid === AetherisContent.SOLUTION_STILL || fluid === AetherisContent.SOLUTION_FLOWING

    /**
     * Nothing displaces a solution, not even water.
     *
     * Vanilla's answer for water is "only downwards, and never by another water". Declining outright is
     * both simpler and truer here: a solution is a reagent, and having a neighbouring water source eat
     * it would destroy chemistry the player set up on purpose.
     */
    override fun canBeReplacedWith(
        state: FluidState,
        level: BlockGetter,
        pos: BlockPos,
        fluid: Fluid,
        direction: Direction,
    ): Boolean = false

    /**
     * The full state: a brim-full cell. Named for the still *texture* it is registered under, not because it
     * is a source - it deliberately is not one.
     *
     * **No state of this fluid is ever a source, and that is load-bearing.** Vanilla's empty bucket aims with
     * `ClipContext.Fluid.SOURCE_ONLY`, and the un-overridable `LiquidBlock.pickupBlock` it then reaches does
     * this:
     *
     *     if (state.getValue(LEVEL) != 0) return ItemStack.EMPTY;
     *     level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
     *     return new ItemStack(this.fluid.getBucket());
     *
     * Note the order: the cell is **destroyed before** the returned stack is looked at, so changing
     * `getBucket` cannot protect it - the block is already gone, and a bucket of acid would simply vanish in
     * a puff. Not being a source is what actually protects it: a SOURCE_ONLY ray never finds these cells, so
     * that path cannot begin. Aiming at a solution is done with a fluid-aware ray instead, which is what the
     * bucket, the test tube and the goggles all use.
     *
     * The knock-on is that vanilla's renderer, which picks the still or flowing sprite by `isSource`, would
     * always choose flowing; `SolutionFluidRenderHandler` answers with the still sprite in both slots.
     */
    class Still : AetherisFluid() {
        override fun getAmount(state: FluidState): Int = MAX_AMOUNT

        override fun isSource(state: FluidState): Boolean = false
    }

    /**
     * The partial state: its level *is* how much liquid it holds, which is what makes the rendered
     * surface sit where the liquid actually does.
     *
     * The property added here is [FlowingFluid.LEVEL] - the fluid's own, whose values are 1..8 - and
     * *not* `LiquidBlock.LEVEL`, which belongs to the block state and reaches 0..15. They carry the same
     * name and opposite senses, and `LiquidBlock`'s constructor sets the fluid one unconditionally when
     * it builds its level lookup table, so omitting it is not a subtle rendering bug: it is an
     * exception during mod initialisation.
     */
    class Flowing : AetherisFluid() {
        override fun createFluidStateDefinition(builder: StateDefinition.Builder<Fluid, FluidState>) {
            super.createFluidStateDefinition(builder)
            builder.add(FlowingFluid.LEVEL)
        }

        override fun getAmount(state: FluidState): Int = state.getValue(FlowingFluid.LEVEL)

        override fun isSource(state: FluidState): Boolean = false
    }

    companion object {
        /** Vanilla's fluid amount at a brim-full cell; `getOwnHeight` divides it by nine. */
        const val MAX_AMOUNT = 8

        private const val SLOPE_FIND_DISTANCE = 4
        private const val DROP_OFF = 1
        private const val TICK_DELAY = 5
        private const val EXPLOSION_RESISTANCE = 100.0f
    }
}
