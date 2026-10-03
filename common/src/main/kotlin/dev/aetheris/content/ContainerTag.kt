package dev.aetheris.content

import dev.aetheris.engine.chem.ContainerCorrosion
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * The container's own state on a stack: what it is made of, how much is left, and how long until the next
 * corrosion step.
 *
 * Kept separate from [MixtureTag] because these are properties of the *vessel* rather than of the chemistry
 * inside it, and the two have different lifetimes: pouring the contents out leaves the corrosion behind.
 *
 * Damage is **derived** from the material rather than stored alongside it, so the number a texture ramp reads
 * cannot disagree with the material it is supposed to describe.
 */
object ContainerTag {

    private const val MATERIAL = "container_material"
    private const val MOLES = "container_moles"
    private const val INITIAL = "container_initial"
    private const val TICKS = "container_ticks"

    /** The container as it is now, or `null` when the stack carries no container state at all. */
    fun materialOf(stack: ItemStack): ContainerCorrosion.Material? {
        val tag = MixtureTag.storedOn(stack) ?: return null
        if (!tag.contains(MATERIAL)) return null
        val initial = if (tag.contains(INITIAL)) tag.getDouble(INITIAL) else tag.getDouble(MOLES)
        if (initial <= 0.0) return null
        return ContainerCorrosion.Material(tag.getString(MATERIAL), tag.getDouble(MOLES), initial)
    }

    fun setMaterial(stack: ItemStack, material: ContainerCorrosion.Material) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) {
            it.putString(MATERIAL, material.speciesId)
            it.putDouble(MOLES, material.moles)
            it.putDouble(INITIAL, material.initialMoles)
        }
    }

    /** Fraction eaten, 0..1, for anything that wants to colour by it. */
    fun damageOf(stack: ItemStack): Double = materialOf(stack)?.damage ?: 0.0

    fun ticksOf(stack: ItemStack): Int = MixtureTag.storedOn(stack)?.getInt(TICKS) ?: 0

    fun setTicks(stack: ItemStack, value: Int) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.putInt(TICKS, value) }
    }
}
