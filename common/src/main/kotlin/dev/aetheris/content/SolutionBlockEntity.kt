package dev.aetheris.content

import dev.aetheris.AetherisCommon
import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.FluidUnits
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.ReactionEngine
import dev.aetheris.engine.world.FlowPacer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * A block that *is* a body of solution.
 *
 * The whole engine [Mixture] - species amounts, volume and temperature - lives in this block
 * entity and is saved to NBT, so a solution survives save/load and can be placed by command.
 * Each reaction interval the entity advances the chemistry one step and lets neighbouring
 * solutions exchange matter, which is what makes "put a reagent block next to it and watch it
 * react" work. Rendering reads [Mixture.solutionColor] from the same NBT, so the colour is the
 * live blend of whatever is dissolved rather than a fixed per-block texture.
 */
class SolutionBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AetherisContent.SOLUTION_BE.get(), pos, state) {

    private val engine: ReactionEngine get() = AetherisCommon.chemistry

    /** The 0-D cell this block represents: one 1 m^3 well-mixed volume. */
    val mixture: Mixture = Mixture(engine.registry, DEFAULT_VOLUME)

    private var ticks = 0
    private var lastSyncedColor: Int? = null
    private var publishedLevel = -1

    /**
     * Banks the fractional part of this cell's flow rate between intervals.
     *
     * Viscosity decides how many spreading steps this cell has earned, and that number is rarely a
     * whole one: a thin liquid earns several per interval, a thick one a fraction of a step.
     */
    private val flowPacer = FlowPacer()

    fun serverTick() {
        if (++ticks % REACTION_INTERVAL_TICKS != 0) return
        engine.step(mixture, REACTION_INTERVAL_TICKS / 20.0)
        // An open puddle is at one atmosphere: anything the chemistry made that the air does not contain
        // bubbles out rather than accumulating as a dissolved gas. See Mixture.ventGases.
        mixture.ventGases()
        mixWithNeighbours()
        flowIntoNeighbours()
        // A falling cell removes itself once it has poured out; there is nothing left to publish.
        if (isRemoved) return
        setChanged()
        syncLevelIfChanged()
        syncAppearanceIfChanged()
    }

    /**
     * Publishes the fill level as a block state so the rendered surface follows the liquid.
     *
     * The state is read back from the level rather than from this entity's own [blockState], because
     * that field is a cache and is not guaranteed to have caught up with a state change made a moment
     * ago - comparing against it would re-issue the same setBlock every tick. [publishedLevel] is
     * what actually stops the repetition.
     */
    private fun syncLevelIfChanged() {
        val world = level ?: return
        if (world.isClientSide) return
        val wanted = SolutionBlock.levelFor(fillFraction())
        if (wanted == publishedLevel) return
        publishedLevel = wanted
        val state = world.getBlockState(blockPos)
        if (state.block !== AetherisContent.SOLUTION.get()) return
        if (state.getValue(LiquidBlock.LEVEL) == wanted) return
        world.setBlock(blockPos, state.setValue(LiquidBlock.LEVEL, wanted), Block.UPDATE_CLIENTS)
    }

    /**
     * Lets a full-enough cell spill into neighbouring empty space, at the pace its viscosity allows.
     *
     * Only one target per spreading step, and only half of what this cell holds, because [equalise]
     * moves half the difference at a time: spilling into several neighbours at once would move more
     * matter than the cell has. Straight down is tried first, since a liquid falls, and the sideways
     * preference rotates so a puddle spreads evenly instead of always creeping the same way.
     *
     * How *often* a step happens is a property of the solution. [Mixture.flowScale] is a rate in steps
     * per second derived from dynamic viscosity, so a thin liquid can spread several cells in one
     * interval while glycerol needs minutes to earn a single step; [FlowPacer] banks the remainder
     * between intervals so the rate is honoured in both directions.
     *
     * Spreading stops once a cell is less than [MIN_FLOW_FILL] full. That gate is what keeps a
     * solution from thinning out across the whole world: each cell hands on half of its contents, so
     * the level halves per step and the chain dies out after a few blocks - the same bounded reach a
     * vanilla fluid has, but with the level being the actual fill fraction rather than a counter.
     */
    private fun flowIntoNeighbours() {
        val world = level ?: return
        if (fillFraction() < MIN_DRAIN_FILL) {
            flowPacer.reset()
            return
        }
        val steps = flowPacer.stepsFor(mixture.flowScale())
        repeat(steps) {
            if (fillFraction() < MIN_DRAIN_FILL) {
                flowPacer.reset()
                return
            }
            if (!takeOneFlowStep(world)) {
                flowPacer.reset()
                return
            }
        }
    }

    /**
     * One step of movement, gravity before everything else. Returns false when the cell cannot move.
     *
     * There are three kinds of step, and the distinction between the first two is the whole point:
     *
     *  - **[FlowKind.FALL]** - empty space below. The cell **pours itself out entirely and disappears**.
     *    Handing over only half, as creeping does, leaves half hanging in mid-air with nothing supporting
     *    it: a solution placed in the air would hover, and liquid running off a ledge would leave a
     *    floating remnant behind to mix back into what it just fell onto. Nothing holds a suspended
     *    liquid up, so nothing should remain.
     *  - **[FlowKind.FEED]** - a solution cell below that is emptier than this one, i.e. communicating
     *    vessels. The two level out, which is what makes a dug hole actually fill instead of receiving one
     *    helping and then watching the cell above go looking sideways.
     *  - **[FlowKind.CREEP]** - sideways into empty space, which *is* a half transfer: creeping is what
     *    thins a puddle, and thinning is what bounds it.
     */
    private fun takeOneFlowStep(world: Level): Boolean {
        val step = selectFlowStep(world) ?: return false
        when (step.kind) {
            FlowKind.FALL -> pourInto(world, step.target)
            FlowKind.FEED, FlowKind.CREEP -> {
                val other = world.getBlockEntity(step.target) as? SolutionBlockEntity ?: return false
                equalise(mixture, other.mixture, 1.0)
                other.syncAppearanceIfChanged()
            }
        }
        return true
    }

    /** Creates a solution cell at [target] holding half of this one, for a sideways creep. */
    private fun spreadInto(world: Level, target: BlockPos) {
        world.setBlock(target, blockState, Block.UPDATE_ALL)
        val created = world.getBlockEntity(target) as? SolutionBlockEntity ?: return
        equalise(mixture, created.mixture, 1.0)
        created.syncAppearanceIfChanged()
    }

    /**
     * Moves everything into [target] and removes this cell.
     *
     * Total, not half, because a falling liquid is not being held up by anything. The block is removed
     * rather than left behind empty, so that what remains in the world is only where the liquid actually
     * is.
     */
    private fun pourInto(world: Level, target: BlockPos) {
        val contents = mixture.snapshot()
        if (contents.isEmpty()) {
            world.removeBlock(blockPos, false)
            return
        }
        world.setBlock(target, blockState, Block.UPDATE_ALL)
        val created = world.getBlockEntity(target) as? SolutionBlockEntity ?: return
        created.mixture.volume = mixture.volume
        created.mixture.temperature = mixture.temperature
        created.mixture.replace(contents)
        // Emptied here, so the client is told before the block goes away - otherwise the last frame it
        // drew would keep this cell's colour.
        mixture.clear()
        created.syncAppearanceIfChanged()
        world.removeBlock(blockPos, false)
    }

    /**
     * What is directly underneath, in words.
     *
     * "It will not flow into the hole" depends entirely on this, and the three cases - empty space, a
     * solution cell that is already as full as this one, and solid rock - are indistinguishable from
     * outside the game. Reported by `/aetheris solution info`.
     */
    fun belowReport(): String {
        val world = level ?: return "unknown"
        val below = blockPos.below()
        val entity = world.getBlockEntity(below) as? SolutionBlockEntity
        if (entity != null) {
            return "solution(fill=${"%.4f".format(entity.fill())})"
        }
        val state = world.getBlockState(below)
        return if (state.canBeReplaced()) "${state.block} (replaceable)" else "${state.block} (blocked)"
    }

    /** How much of this cell's volume is condensed matter, 0..1; the liquid's "level". */
    private fun fillFraction(): Double {
        if (mixture.volume <= 0.0) return 0.0
        return mixture.condensedVolume() / mixture.volume
    }

    /**
     * Where this cell would spread next, or `null` if it cannot move.
     *
     * Exposed because "does not flow" has several causes that look identical in the world - the cell is
     * walled in, too thin to creep sideways, or has already drained as far as it can - and telling them
     * apart from a screenshot is guesswork. `/aetheris solution info` prints the answer.
     *
     * Gravity and sideways creep are gated differently, and that asymmetry is the point. A thin film
     * still runs down a hole, so draining is allowed almost down to nothing; only a reasonably deep
     * body of liquid creeps sideways, which is what stops a puddle smearing thin across the world. Using
     * one threshold for both was wrong in a way that took a dug hole to reveal: a puddle that had spread
     * four times was below the sideways floor, so it stopped draining too, and a hole directly beneath
     * it stayed dry.
     */
    fun nextFlowTarget(): BlockPos? = level?.let { selectFlowStep(it)?.target }

    /**
     * Where this cell would move next, and whether that means feeding an existing cell or creating one.
     *
     * Single source of truth for both movement and the diagnostic, so that what `/aetheris solution info`
     * prints cannot drift away from what the cell actually does - which is the whole reason the command
     * exists.
     */
    private fun selectFlowStep(world: Level): FlowStep? {
        val fill = fillFraction()
        val below = blockPos.below()
        val belowEntity = world.getBlockEntity(below) as? SolutionBlockEntity
        if (belowEntity != null) {
            // Gravity's second form: a fuller cell keeps feeding an emptier one below until they match.
            if (fill > belowEntity.fill() + DRY_EPSILON) return FlowStep(below, FlowKind.FEED)
        } else if (fill >= MIN_DRAIN_FILL && canFlowInto(world, below)) {
            return FlowStep(below, FlowKind.FALL)
        }

        if (fill < MIN_FLOW_FILL) return null
        val offset = (ticks / REACTION_INTERVAL_TICKS) % HORIZONTALS.size
        for (index in HORIZONTALS.indices) {
            val candidate = blockPos.relative(HORIZONTALS[(index + offset) % HORIZONTALS.size])
            if (canFlowInto(world, candidate)) return FlowStep(candidate, FlowKind.CREEP)
        }
        return null
    }

    /** What a step does when it gets there: see the note on [takeOneFlowStep]. */
    private enum class FlowKind { FALL, FEED, CREEP }

    private data class FlowStep(val target: BlockPos, val kind: FlowKind)

    /** How full this cell is, 0..1, for diagnostics. */
    fun fill(): Double = fillFraction()

    private fun canFlowInto(level: Level, target: BlockPos): Boolean {
        if (!level.isLoaded(target)) return false
        val state = level.getBlockState(target)
        // Another solution is mixed with, never overwritten: replacing it would destroy its contents.
        if (state.block === AetherisContent.SOLUTION.get()) return false
        return state.canBeReplaced()
    }

    /**
     * Sends this block entity to clients when the solution's appearance changed.
     *
     * The renderer reads the client's copy of this NBT, and nothing sends it again after placement,
     * so without this a reacting solution keeps whatever colour it had when it was placed - the
     * block would look inert while the chemistry behind it ran. Comparing against the last colour
     * sent keeps a bench full of solutions from pushing a packet every second for no visible change.
     */
    /**
     * Tells clients the colour changed.
     *
     * Public because anything that edits a mixture has to call it: the cell's own tick does, and so do the
     * container interactions - a tube pouring a sample in changes the colour with no tick having run, and without
     * this the change would not reach anybody until the next periodic sync.
     */
    fun syncAppearanceIfChanged() {
        val level = level ?: return
        if (level.isClientSide) return
        val color = mixture.solutionColor()
        if (color == lastSyncedColor) return
        lastSyncedColor = color
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /**
     * Equilibrates this cell with adjacent solution blocks.
     *
     * This stands in for diffusion between touching bodies of liquid, which is why it is deliberately
     * *not* scaled by viscosity: viscosity governs how a liquid *flows* ([flowIntoNeighbours]), while
     * two liquids in contact exchange matter by a different mechanism that needs its own model before
     * it can be given a rate.
     */
    private fun mixWithNeighbours() {
        val level = level ?: return
        for (direction in Direction.values()) {
            val neighbour = level.getBlockEntity(blockPos.relative(direction)) as? SolutionBlockEntity ?: continue
            equalise(mixture, neighbour.mixture, MIXING_FRACTION)
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        MixtureTag.write(tag, mixture)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        mixture.volume = if (tag.contains("volume")) tag.getDouble("volume").coerceAtLeast(1e-6) else DEFAULT_VOLUME
        MixtureTag.read(tag, mixture)
    }

    /** Sends the composition to clients on block update, so the renderer can tint immediately. */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    /**
     * The packet that carries this entity to clients whenever the block changes.
     *
     * `BlockEntity` returns `null` here by default, and that default is silent: the client still
     * creates the block entity, just with an empty mixture. A placed solution therefore rendered
     * pure white - the tint comes from the client's copy of the composition, and the client never
     * received one. [getUpdateTag] alone is not enough either, because it is only consulted when a
     * whole chunk is sent, which is why the colour appeared only after a chunk reload.
     */
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /**
         * The volume of one cell: one bucket, one litre.
         *
         * Not a cubic metre, because nothing counts fluid in cubic metres - vanilla counts a source block
         * as a bucket and Create counts a bucket as a litre, so a block of solution holds a litre too. That
         * is what makes a bucket fill a block exactly, and what makes 55.5 mol of water - a litre, and the
         * number a chemist writes without thinking - fill one as well.
         */
        const val DEFAULT_VOLUME = FluidUnits.CELL_VOLUME
        const val REACTION_INTERVAL_TICKS = 20
        const val MIXING_FRACTION = 0.25

        /**
         * A cell below this fraction of its volume in condensed matter stops creeping *sideways*.
         *
         * Because each spreading step hands on half the contents, a full cell reaches roughly four
         * blocks before thinning below this line. Without a floor the solution would smear across every
         * reachable air block as an invisible film.
         */
        const val MIN_FLOW_FILL = 1.0 / 16.0

        /**
         * A cell below this fraction stops moving at all, *including downwards*.
         *
         * Much lower than [MIN_FLOW_FILL] on purpose: gravity does not care how deep a puddle is, so a
         * film that has spread itself thin must still run into a hole. Only the sideways creep is worth
         * rationing. This floor exists solely to stop a near-empty cell spawning a chain of emptier ones
         * all the way down to bedrock.
         */
        const val MIN_DRAIN_FILL = 1.0 / 1024.0

        /**
         * Slack for "is this cell fuller than the one below".
         *
         * Without it, two cells that equalise to within floating-point dust keep offering each other a
         * transfer of nothing, every tick, forever. It exists to stop that, not to model anything.
         */
        const val DRY_EPSILON = 1e-9

        /** Sideways flow directions, tried in a rotating order so puddles spread evenly. */
        private val HORIZONTALS = arrayOf(
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST,
        )

        /** Moves each species from the richer cell toward the poorer one, conserving total moles. */
        fun equalise(a: Mixture, b: Mixture, fraction: Double) {
            for (id in a.speciesIds + b.speciesIds) {
                val difference = a.amount(id) - b.amount(id)
                if (difference == 0.0) continue
                val moved = difference * fraction * 0.5
                a.add(id, -moved)
                b.add(id, moved)
            }
        }
    }
}
