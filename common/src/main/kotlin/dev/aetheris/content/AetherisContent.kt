package dev.aetheris.content

import dev.aetheris.Aetheris
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import java.util.function.Supplier

/**
 * The apparatus layer: the bench glassware that converts between Minecraft's two representations
 * of matter (items/blocks) and the engine's compartments. Declared once here; the loaders adapt it
 * through [Registrar].
 *
 * `conduit` is the first *block*, which exercises the block-registration path the gas field will
 * eventually need.
 */
object AetherisContent {

    lateinit var TEST_TUBE: Supplier<Item>
        private set
    lateinit var BEAKER: Supplier<Item>
        private set
    lateinit var GLASS_ROD: Supplier<Item>
        private set
    lateinit var CONDUIT: Supplier<Block>
        private set
    lateinit var CONDUIT_ITEM: Supplier<Item>
        private set

    /** The carrier block for a body of solution; its block entity holds the chemistry. */
    lateinit var SOLUTION: Supplier<Block>
        private set
    lateinit var SOLUTION_BE: Supplier<BlockEntityType<SolutionBlockEntity>>
        private set
    lateinit var SOLUTION_ITEM: Supplier<Item>
        private set

    lateinit var MAIN_TAB: Supplier<CreativeModeTab>
        private set

    fun id(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Aetheris.MOD_ID, path)

    /** Declares every Aetheris content object against [registrar]; call once per loader. */
    fun define(registrar: Registrar) {
        TEST_TUBE = registrar.item("test_tube", Item.Properties())
        BEAKER = registrar.item("beaker", Item.Properties())
        GLASS_ROD = registrar.item("glass_rod", Item.Properties())

        CONDUIT = registrar.block(
            "conduit",
            BlockBehaviour.Properties.of().strength(0.3f).sound(SoundType.GLASS),
        )
        CONDUIT_ITEM = registrar.blockItem("conduit", CONDUIT, Item.Properties())

        SOLUTION = registrar.block(
            "solution",
            BlockBehaviour.Properties.of().strength(0.2f).sound(SoundType.GLASS).noOcclusion(),
        ) { properties -> SolutionBlock(properties) }
        SOLUTION_BE = registrar.blockEntity(
            name = "solution",
            blocks = { arrayOf(SOLUTION.get()) },
            factory = { pos, state -> SolutionBlockEntity(pos, state) },
        )
        SOLUTION_ITEM = registrar.blockItem("solution", SOLUTION, Item.Properties())

        MAIN_TAB = registrar.creativeTab(
            name = "main",
            titleKey = "itemGroup.${Aetheris.MOD_ID}.main",
            icon = { ItemStack(TEST_TUBE.get()) },
            entries = { listOf(TEST_TUBE, BEAKER, GLASS_ROD, CONDUIT_ITEM, SOLUTION_ITEM) },
        )
    }
}
