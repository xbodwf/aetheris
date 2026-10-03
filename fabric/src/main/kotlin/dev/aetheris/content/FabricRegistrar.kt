package dev.aetheris.content

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import java.util.function.Supplier

/** Fabric adapter: its registries accept entries during mod init, so registration is immediate. */
class FabricRegistrar : Registrar {

    override fun item(name: String, properties: Item.Properties): Supplier<Item> =
        constant(Registry.register(BuiltInRegistries.ITEM, AetherisContent.id(name), Item(properties)))

    override fun item(
        name: String,
        properties: Item.Properties,
        factory: (Item.Properties) -> Item,
    ): Supplier<Item> =
        constant(Registry.register(BuiltInRegistries.ITEM, AetherisContent.id(name), factory(properties)))

    override fun block(name: String, properties: BlockBehaviour.Properties): Supplier<Block> =
        constant(Registry.register(BuiltInRegistries.BLOCK, AetherisContent.id(name), Block(properties)))

    override fun block(
        name: String,
        properties: BlockBehaviour.Properties,
        factory: (BlockBehaviour.Properties) -> Block,
    ): Supplier<Block> =
        constant(Registry.register(BuiltInRegistries.BLOCK, AetherisContent.id(name), factory(properties)))

    override fun blockItem(
        name: String,
        block: Supplier<Block>,
        properties: Item.Properties,
    ): Supplier<Item> = constant(
        Registry.register(
            BuiltInRegistries.ITEM,
            AetherisContent.id(name),
            BlockItem(block.get(), properties),
        ),
    )

    override fun fluid(name: String, fluid: Fluid): Supplier<Fluid> =
        constant(Registry.register(BuiltInRegistries.FLUID, AetherisContent.id(name), fluid))

    override fun <T : BlockEntity> blockEntity(
        name: String,
        blocks: () -> Array<Block>,
        factory: (BlockPos, BlockState) -> T,
    ): Supplier<BlockEntityType<T>> = constant(
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            AetherisContent.id(name),
            BlockEntityType.Builder.of({ pos, state -> factory(pos, state) }, *blocks()).build(),
        ),
    )

    override fun creativeTab(
        name: String,
        titleKey: String,
        icon: Supplier<ItemStack>,
        entries: () -> List<Supplier<Item>>,
    ): Supplier<CreativeModeTab> = constant(
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            AetherisContent.id(name),
            FabricItemGroup.builder()
                .title(Component.translatable(titleKey))
                .icon(icon)
                .displayItems { _, output -> entries().forEach { output.accept(it.get()) } }
                .build(),
        ),
    )

    private fun <T> constant(value: T): Supplier<T> = Supplier { value }
}
