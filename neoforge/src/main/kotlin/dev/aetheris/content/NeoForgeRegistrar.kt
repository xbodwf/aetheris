package dev.aetheris.content

import dev.aetheris.Aetheris
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
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
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * NeoForge adapter: registration is deferred until the mod's registry events fire, which is why
 * every method hands back a [Supplier] instead of the object itself.
 */
class NeoForgeRegistrar(eventBus: IEventBus) : Registrar {

    private val items = DeferredRegister.create(Registries.ITEM, Aetheris.MOD_ID)
    private val blocks = DeferredRegister.create(Registries.BLOCK, Aetheris.MOD_ID)
    private val blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Aetheris.MOD_ID)
    private val tabs = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Aetheris.MOD_ID)
    private val fluids = DeferredRegister.create(Registries.FLUID, Aetheris.MOD_ID)

    init {
        items.register(eventBus)
        blocks.register(eventBus)
        blockEntities.register(eventBus)
        tabs.register(eventBus)
        fluids.register(eventBus)
    }

    override fun item(name: String, properties: Item.Properties): Supplier<Item> =
        items.register(name, Supplier { Item(properties) })

    override fun item(
        name: String,
        properties: Item.Properties,
        factory: (Item.Properties) -> Item,
    ): Supplier<Item> = items.register(name, Supplier { factory(properties) })

    override fun block(name: String, properties: BlockBehaviour.Properties): Supplier<Block> =
        blocks.register(name, Supplier { Block(properties) })

    override fun block(
        name: String,
        properties: BlockBehaviour.Properties,
        factory: (BlockBehaviour.Properties) -> Block,
    ): Supplier<Block> = blocks.register(name, Supplier { factory(properties) })

    override fun blockItem(
        name: String,
        block: Supplier<Block>,
        properties: Item.Properties,
    ): Supplier<Item> = items.register(name, Supplier { BlockItem(block.get(), properties) })

    override fun fluid(name: String, fluid: Fluid): Supplier<Fluid> =
        fluids.register(name, Supplier { fluid })

    override fun <T : BlockEntity> blockEntity(
        name: String,
        blocks: () -> Array<Block>,
        factory: (BlockPos, BlockState) -> T,
    ): Supplier<BlockEntityType<T>> = blockEntities.register(
        name,
        // NeoForge's BuildType carries a datafixer Type argument; null is the usual mod value.
        Supplier { BlockEntityType.Builder.of({ pos, state -> factory(pos, state) }, *blocks()).build(null) },
    )

    override fun creativeTab(
        name: String,
        titleKey: String,
        icon: Supplier<ItemStack>,
        entries: () -> List<Supplier<Item>>,
    ): Supplier<CreativeModeTab> = tabs.register(
        name,
        Supplier {
            CreativeModeTab.builder()
                .title(Component.translatable(titleKey))
                .icon(icon)
                .displayItems { _, output -> entries().forEach { output.accept(it.get()) } }
                .build()
        },
    )
}
