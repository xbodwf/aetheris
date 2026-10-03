package dev.aetheris.content

import net.minecraft.core.BlockPos
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Supplier

/**
 * The one wheel the loaders share: every piece of Aetheris content is declared exactly once
 * against this interface, in [AetherisContent], and each loader only supplies an adapter.
 *
 * Every method returns a [Supplier] rather than the object itself because NeoForge registers
 * lazily: its objects do not exist until the registry event fires. Fabric registers immediately
 * and simply wraps the finished object in a constant supplier. Returning suppliers lets the shared
 * content code stay identical on both.
 */
interface Registrar {

    fun item(name: String, properties: Item.Properties): Supplier<Item>

    fun block(name: String, properties: BlockBehaviour.Properties): Supplier<Block>

    /** Registers a block of a specific class (e.g. one with a block entity). */
    fun block(
        name: String,
        properties: BlockBehaviour.Properties,
        factory: (BlockBehaviour.Properties) -> Block,
    ): Supplier<Block>

    /** Registers a [Item] that places [block]; the block itself is registered separately. */
    fun blockItem(name: String, block: Supplier<Block>, properties: Item.Properties): Supplier<Item>

    /**
     * Registers a [BlockEntityType] for the given [blocks].
     *
     * The factory is only invoked when a block entity is actually created, so it is safe for it
     * to read content that is still being registered (its own type, for instance).
     */
    fun <T : BlockEntity> blockEntity(
        name: String,
        blocks: () -> Array<Block>,
        factory: (BlockPos, BlockState) -> T,
    ): Supplier<BlockEntityType<T>>

    fun creativeTab(
        name: String,
        titleKey: String,
        icon: Supplier<ItemStack>,
        entries: () -> List<Supplier<Item>>,
    ): Supplier<CreativeModeTab>
}
