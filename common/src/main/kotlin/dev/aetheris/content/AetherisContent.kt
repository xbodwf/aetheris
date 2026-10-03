package dev.aetheris.content

import dev.aetheris.Aetheris
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.PushReaction
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

    /**
     * The fluid instances themselves.
     *
     * Eagerly created plain values rather than [Supplier]s, because [SolutionBlock]'s constructor needs
     * the very same object and NeoForge's deferred holders cannot be resolved that early. Vanilla does
     * the same thing with `Fluids.WATER`. Nothing here touches a registry, so the circularity - the
     * fluid must know its block, the block must know its fluid - is broken by the fluid's reference to
     * the block staying lazy.
     */
    val SOLUTION_STILL: AetherisFluid.Still = AetherisFluid.Still()
    val SOLUTION_FLOWING: AetherisFluid.Flowing = AetherisFluid.Flowing()

    lateinit var TEST_TUBE: Supplier<Item>
        private set
    lateinit var BEAKER: Supplier<Item>
        private set
    lateinit var GLASS_ROD: Supplier<Item>
        private set

    /** Worn on the head, these let the wearer read what a solution contains. */
    lateinit var GOGGLES: Supplier<Item>
        private set
    lateinit var CONDUIT: Supplier<Block>
        private set
    lateinit var CONDUIT_ITEM: Supplier<Item>
        private set

    /** The carrier block for a body of solution; its block entity holds the chemistry. */
    lateinit var SOLUTION: Supplier<Block>
        private set

    /** The container a solution is moved with; a fluid has no block item, only this. */
    lateinit var SOLUTION_BUCKET: Supplier<Item>
        private set
    lateinit var SOLUTION_BE: Supplier<BlockEntityType<SolutionBlockEntity>>
        private set

    lateinit var MAIN_TAB: Supplier<CreativeModeTab>
        private set

    fun id(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Aetheris.MOD_ID, path)

    /** Declares every Aetheris content object against [registrar]; call once per loader. */
    fun define(registrar: Registrar) {
        // One per player: a tube carries a sample, and stacking samples would mean stacking chemistry.
        TEST_TUBE = registrar.item("test_tube", Item.Properties().stacksTo(1)) { TestTubeItem(it) }
        BEAKER = registrar.item("beaker", Item.Properties())
        GLASS_ROD = registrar.item("glass_rod", Item.Properties())
        // Armour stacks to one, like every other helmet.
        GOGGLES = registrar.item("chemists_goggles", Item.Properties().stacksTo(1)) { ChemistsGogglesItem(it) }

        CONDUIT = registrar.block(
            "conduit",
            BlockBehaviour.Properties.of().strength(0.3f).sound(SoundType.GLASS),
        )
        CONDUIT_ITEM = registrar.blockItem("conduit", CONDUIT, Item.Properties())

        // The fluid has to exist before the block that represents it, exactly as vanilla registers
        // `minecraft:water` before `minecraft:water` the block.
        registrar.fluid("solution", SOLUTION_STILL)
        registrar.fluid("flowing_solution", SOLUTION_FLOWING)

        // Modelled on vanilla's `LiquidBlock` down to the properties, because that is where the fluid
        // feel actually comes from: inherited, not reimplemented. `LiquidBlock` supplies the empty shape
        // that keeps a fluid out of the targeting ray, the invisible render shape that hands drawing to
        // the fluid renderer, and the fluid-state lookup that turns LEVEL into a surface height.
        //
        // No `randomTicks()`: vanilla would spread the fluid itself, and its spread cannot carry a block
        // entity. See SolutionBlock.isRandomlyTicking.
        //
        // Strength is 100 rather than bedrock's -1: with no shape to aim at it is unreachable by hand
        // either way, and this keeps it destructible by the things that *should* still remove it.
        SOLUTION = registrar.block(
            "solution",
            BlockBehaviour.Properties.of()
                .strength(100.0f, 3_600_000.0f)
                .sound(SoundType.EMPTY)
                .noCollission()
                .noOcclusion()
                .liquid()
                .replaceable()
                .pushReaction(PushReaction.DESTROY)
                .noLootTable(),
        ) { properties -> SolutionBlock(SOLUTION_STILL, properties) }
        SOLUTION_BE = registrar.blockEntity(
            name = "solution",
            blocks = { arrayOf(SOLUTION.get()) },
            factory = { pos, state -> SolutionBlockEntity(pos, state) },
        )
        // A fluid has no block item in vanilla, only a bucket - and the bucket is what moves a solution
        // without destroying what is dissolved in it.
        SOLUTION_BUCKET = registrar.item(
            "solution_bucket",
            Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET),
        ) { SolutionBucketItem(SOLUTION_STILL, it) }

        MAIN_TAB = registrar.creativeTab(
            name = "main",
            titleKey = "itemGroup.${Aetheris.MOD_ID}.main",
            icon = { ItemStack(TEST_TUBE.get()) },
            entries = { listOf(TEST_TUBE, BEAKER, GLASS_ROD, CONDUIT_ITEM, SOLUTION_BUCKET, GOGGLES) },
        )
    }
}
