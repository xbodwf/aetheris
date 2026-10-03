package dev.aetheris.content

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.Item

/**
 * Bench goggles. Worn on the head, they let the wearer read what a solution actually contains.
 *
 * The shape of this follows Create's engineer's goggles, which is the established way to do "wearing a
 * thing changes what you can see" in this game: an armour item in the head slot plus one static question,
 * [isWornBy], that the client overlay - and anything else that wants to know - can ask. Carrying them is
 * deliberately not enough; you have to put them on.
 *
 * Everything about *what* gets shown lives on the client and in [dev.aetheris.engine.chem.SolutionNames];
 * this class only answers whether the wearer qualifies.
 */
class ChemistsGogglesItem(properties: Item.Properties) :
    ArmorItem(ArmorMaterials.LEATHER, ArmorItem.Type.HELMET, properties) {

    companion object {
        /** True when [entity] has a pair on its head. */
        fun isWornBy(entity: LivingEntity?): Boolean =
            entity != null && entity.getItemBySlot(EquipmentSlot.HEAD).item is ChemistsGogglesItem
    }
}
