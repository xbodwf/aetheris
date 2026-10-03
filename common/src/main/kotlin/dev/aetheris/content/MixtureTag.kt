package dev.aetheris.content

import dev.aetheris.engine.chem.Mixture
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * The serialised form of a [Mixture]: what a solution looks like on disk and inside an item.
 *
 * One format, three users that must agree - the block entity's own save, the bucket, and the test tube.
 * Keeping it in one place is what guarantees that a solution scooped into a bucket comes back out exactly
 * as it went in, rather than merely similar.
 *
 * Only species the registry knows are read back. A save written before a data pack removed a substance
 * would otherwise fail to load at all, and losing an unknown trace is much better than losing the world.
 */
object MixtureTag {

    private const val VOLUME = "volume"
    private const val TEMPERATURE = "temperature"
    private const val SPECIES = "species"

    fun write(tag: CompoundTag, mixture: Mixture) {
        tag.putDouble(VOLUME, mixture.volume)
        tag.putDouble(TEMPERATURE, mixture.temperature)
        val species = CompoundTag()
        for ((id, moles) in mixture.snapshot()) species.putDouble(id, moles)
        tag.put(SPECIES, species)
    }

    /** Applies [tag] to [mixture], leaving anything the tag omits as it already was. */
    fun read(tag: CompoundTag, mixture: Mixture) {
        if (tag.contains(VOLUME)) mixture.volume = tag.getDouble(VOLUME).coerceAtLeast(1e-6)
        if (tag.contains(TEMPERATURE)) mixture.temperature = tag.getDouble(TEMPERATURE).coerceAtLeast(1.0)
        val species = tag.getCompound(SPECIES)
        val loaded = LinkedHashMap<String, Double>()
        for (id in species.allKeys) {
            if (mixture.registry.contains(id)) loaded[id] = species.getDouble(id)
        }
        mixture.replace(loaded)
    }

    /**
     * Stores [mixture] on [stack].
     *
     * `CUSTOM_DATA` is vanilla's own slot for a mod's item payload, which is why no component type has to
     * be registered for this: the container is generic, only its contents are ours.
     */
    fun store(stack: ItemStack, mixture: Mixture) {
        val tag = CompoundTag()
        write(tag, mixture)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
    }

    /** The mixture stored on [stack], or `null` when it carries none - an empty container. */
    fun storedOn(stack: ItemStack): CompoundTag? = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()

    /** Empties [stack] of its contents. */
    fun clearFrom(stack: ItemStack) {
        stack.remove(DataComponents.CUSTOM_DATA)
    }

    /**
     * Empties the *contents* while leaving the container's own state alone.
     *
     * A bucket that has been eaten by acid stays eaten after it is poured out - the rust is the vessel's, not the
     * liquid's - so clearing the whole component on every pour would quietly repair it.
     */
    fun clearContents(stack: ItemStack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag ->
            tag.remove(VOLUME)
            tag.remove(TEMPERATURE)
            tag.remove(SPECIES)
        }
    }
}
