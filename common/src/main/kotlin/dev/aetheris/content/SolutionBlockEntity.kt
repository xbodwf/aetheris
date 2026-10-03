package dev.aetheris.content

import dev.aetheris.AetherisCommon
import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.Mixture
import dev.aetheris.engine.chem.ReactionEngine
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
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

    fun serverTick() {
        if (++ticks % REACTION_INTERVAL_TICKS != 0) return
        engine.step(mixture, REACTION_INTERVAL_TICKS / 20.0)
        mixWithNeighbours()
        setChanged()
    }

    private fun mixWithNeighbours() {
        val level = level ?: return
        for (direction in Direction.values()) {
            val neighbour = level.getBlockEntity(blockPos.relative(direction)) as? SolutionBlockEntity ?: continue
            equalise(mixture, neighbour.mixture, MIXING_FRACTION)
        }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putDouble("volume", mixture.volume)
        tag.putDouble("temperature", mixture.temperature)
        val species = CompoundTag()
        for ((id, moles) in mixture.snapshot()) species.putDouble(id, moles)
        tag.put("species", species)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        mixture.volume = if (tag.contains("volume")) tag.getDouble("volume").coerceAtLeast(1e-6) else DEFAULT_VOLUME
        mixture.temperature = if (tag.contains("temperature")) {
            tag.getDouble("temperature").coerceAtLeast(1.0)
        } else {
            Constants.T0
        }
        val species = tag.getCompound("species")
        val loaded = LinkedHashMap<String, Double>()
        for (id in species.allKeys) {
            if (engine.registry.contains(id)) loaded[id] = species.getDouble(id)
        }
        mixture.replace(loaded)
    }

    /** Sends the composition to clients on block update, so the renderer can tint immediately. */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    companion object {
        const val DEFAULT_VOLUME = 1.0
        const val REACTION_INTERVAL_TICKS = 20
        const val MIXING_FRACTION = 0.25

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
