package dev.aetheris.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import dev.aetheris.Aetheris
import dev.aetheris.AetherisCommon
import dev.aetheris.content.AetherisContent
import dev.aetheris.content.SolutionBlock
import dev.aetheris.content.SolutionBlockEntity
import dev.aetheris.engine.Constants
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.Species
import dev.aetheris.engine.chem.SpeciesLookup
import dev.aetheris.engine.chem.SpeciesRegistry
import dev.aetheris.engine.chem.WetChemistry
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock

/**
 * Aetheris operator commands.
 *
 * Written against vanilla Brigadier types only, so each loader can register the same tree through
 * its own hook and nothing here knows which loader is running.
 *
 * `/aetheris solution clear [radius]` exists because solution blocks are unbreakable by design: a
 * liquid is not mined, and a stray swing must not silently delete a reacting mixture. The radius is
 * bounded rather than global because a command cannot walk an entire world - there is no API that
 * enumerates every placed block, only the chunks currently loaded. So this edits what is loaded
 * within `radius` of the caller, which for clearing a spill or resetting a bench is what is wanted.
 */
object AetherisCommands {

    const val DEFAULT_RADIUS = 16
    const val MAX_RADIUS = 128

    /** `/aetheris solution info` searches this far by default; small, because it is a look-at tool. */
    const val DEFAULT_INFO_RADIUS = 4

    /** Caps so a large spill cannot flood the chat. */
    private const val MAX_INFO_LINES = 12
    private const val MAX_SPECIES_SHOWN = 6

    /** The solvent for `/aetheris solution fill`; the rest of the aqueous slice plugs into it. */
    private const val WATER_ID = WetChemistry.WATER

    /**
     * Litres in one cell.
     *
     * One, because a cell is a bucket and a bucket is a litre - see [FluidUnits]. This is the constant that
     * keeps `/aetheris solution fill` honest: molarity times this is the mole count, so a recipe written in
     * mol/L lands in the cell at exactly the concentration asked for.
     */
    private const val LITRES_PER_CELL = 1.0

    /** A few radii worth offering, since the argument's range is far wider than anyone wants to type. */
    private val RADIUS_CHOICES = listOf(4, DEFAULT_RADIUS, 32, 64)

    private val WHITESPACE = Regex("\\s+")

    private val BAD_SOLUTE = DynamicCommandExceptionType { argument ->
        Component.literal("Expected <species>=<molarity>, for example aetheris:copper_2_ion=0.5 - was '$argument'")
    }
    private val BAD_MOLARITY = DynamicCommandExceptionType { argument ->
        Component.literal("Molarity must be a number in mol per litre - was '$argument'")
    }
    private val UNKNOWN_SPECIES = DynamicCommandExceptionType { argument ->
        Component.literal(
            "Unknown species or formula '$argument' - try a formula like HCl or CuSO4, " +
                "or see /aetheris solution info for what exists",
        )
    }

    /** Phases a solute may be read as, in the order somebody would mean them: dissolved, then solid, then
     * liquid, then gas. The same order the tab completion offers them in. */
    private val SOLUTE_PHASES = listOf(Phase.AQUEOUS, Phase.SOLID, Phase.LIQUID, Phase.GAS)
    private val NOT_A_LIQUID = DynamicCommandExceptionType { argument ->
        Component.literal("'$argument' is not a liquid, so nothing can be dissolved in it")
    }
    private val NOT_A_SOLUTION = SimpleCommandExceptionType(
        Component.literal("Could not create a solution there"),
    )

    /** The medium named on the `in` branch, for the two lambdas that need it. */
    private fun solventOf(context: CommandContext<CommandSourceStack>): String =
        StringArgumentType.getString(context, "solvent")

    /**
     * What one entry contributes to the mixture: one substance, or the ions a formula decomposes into.
     *
     * The decision belongs to the engine (see `SpeciesLookup`), because which interpretation a formula should
     * take is chemistry rather than command plumbing - and because that is where it can be tested against the
     * shipped catalogue instead of against a registry a test invented.
     */
    private fun contribution(registry: SpeciesRegistry, name: String): Map<String, Double>? =
        SpeciesLookup.contribution(registry, name)

    /** The medium named on the `in` branch: one liquid, by id, bare name or formula. */
    private fun liquid(registry: SpeciesRegistry, name: String): Species? =
        SpeciesLookup.liquid(registry, name)

    /** Registers the `/aetheris` tree on [dispatcher]. */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("aetheris")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.literal("solution")
                        .then(
                            Commands.literal("clear")
                                .executes { clear(it, DEFAULT_RADIUS) }
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                        .executes { clear(it, IntegerArgumentType.getInteger(it, "radius")) },
                                ),
                        )
                        .then(
                            Commands.literal("info")
                                .executes { info(it, DEFAULT_INFO_RADIUS) }
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                        .suggests { _, builder ->
                                            RADIUS_CHOICES.forEach { builder.suggest(it) }
                                            builder.buildFuture()
                                        }
                                        .executes { info(it, IntegerArgumentType.getInteger(it, "radius")) },
                                ),
                        )
                        .then(
                            Commands.literal("fill")
                                .then(
                                    Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(
                                            Commands.argument("solutes", StringArgumentType.greedyString())
                                                .suggests { _, builder -> SpeciesSuggestions.solutes(builder) }
                                                .executes { fill(it, WATER_ID, StringArgumentType.getString(it, "solutes")) },
                                        )
                                        // `<pos> in <liquid> <solutes>`: what a solution is dissolved *in*
                                        // is the whole subject here, and water is only the default. The
                                        // medium has to come before the solutes because those are greedy -
                                        // a trailing literal could never be reached.
                                        .then(
                                            Commands.literal("in")
                                                .then(
                                                    Commands.argument("solvent", StringArgumentType.word())
                                                        .suggests { _, builder -> SpeciesSuggestions.liquids(builder) }
                                                        // Naming a medium and no solutes is a legitimate
                                                        // request: a cell of the pure liquid is what you put
                                                        // next to another one to compare how they flow.
                                                        .executes { fill(it, solventOf(it), "") }
                                                        .then(
                                                            Commands.argument("solutes", StringArgumentType.greedyString())
                                                                .suggests { _, builder -> SpeciesSuggestions.solutes(builder) }
                                                                .executes {
                                                                    fill(it, solventOf(it), StringArgumentType.getString(it, "solutes"))
                                                                },
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )
    }

    /**
     * Reports what the solution cells around the caller actually hold.
     *
     * This exists because the interesting failures here are invisible from the outside. A cell with
     * nothing in it renders as a thin white film at exactly one ninth of a block - which is also what a
     * legitimately shallow puddle looks like - and "it will not flow" is equally consistent with "it is
     * walled in" and "it is too thin to be worth moving". Reading the mixture, the fill fraction and
     * the next flow target settles all of it in one command.
     */
    private fun info(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val level = source.level
        val centre = BlockPos.containing(source.position)
        val solution = AetherisContent.SOLUTION.get()
        val cursor = BlockPos.MutableBlockPos()
        var found = 0
        var reported = 0
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    cursor.set(centre.x + dx, centre.y + dy, centre.z + dz)
                    if (!level.isLoaded(cursor)) continue
                    if (level.getBlockState(cursor).block !== solution) continue
                    found++
                    val entity = level.getBlockEntity(cursor) as? SolutionBlockEntity
                    // Cap the output: a spilled puddle can be dozens of cells, and a wall of text helps
                    // nobody.
                    if (entity == null || reported >= MAX_INFO_LINES) continue
                    reported++
                    val position = cursor.immutable()
                    val line = describe(entity)
                    source.sendSuccess({ Component.literal("${position.toShortString()}  $line") }, false)
                }
            }
        }
        val total = found
        val shown = reported
        source.sendSuccess(
            {
                Component.literal(
                    if (total == 0) {
                        "Aetheris: no solution within $radius blocks"
                    } else {
                        "Aetheris: $total solution block(s), showing $shown"
                    },
                )
            },
            false,
        )
        return total
    }

    private fun describe(entity: SolutionBlockEntity): String {
        val mixture = entity.mixture
        val fill = entity.fill()
        val species = mixture.snapshot().entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(MAX_SPECIES_SHOWN)
            .joinToString(" ") { (id, moles) -> "$id=${"%.4g".format(moles)}" }
        val viscosity = mixture.liquidViscosity()
        val target = entity.nextFlowTarget()
        return buildString {
            append("fill=").append("%.4f".format(fill))
            append(" level=").append(SolutionBlock.levelFor(fill))
            append(" flow=").append("%.3f".format(mixture.flowScale()))
            append(" viscosity=").append(viscosity?.let { "%.4g".format(it) } ?: "unknown")
            append(" next=").append(target?.toShortString() ?: "none")
            append(" below=").append(entity.belowReport())
            append(" species={").append(species.ifEmpty { "EMPTY" }).append('}')
            append("  -- ").append(hint(fill, target != null))
        }
    }

    /**
     * Why a cell is or is not moving, in words.
     *
     * `next=none` has two causes that look identical in the world and have opposite fixes - walled in,
     * or too thin to be worth moving - and the raw numbers do not say which. The threshold is named
     * here because it is otherwise a magic 1/16 with no way for a player to discover it.
     */
    private fun hint(fill: Double, hasTarget: Boolean): String = when {
        hasTarget -> "can flow"
        fill < SolutionBlockEntity.MIN_DRAIN_FILL ->
            "too thin to move at all (needs fill >= ${SolutionBlockEntity.MIN_DRAIN_FILL})"
        fill < SolutionBlockEntity.MIN_FLOW_FILL ->
            "too thin to creep sideways, but would still drain straight down"
        fill > 1.0 -> "over-full: the cell volume is smaller than its contents"
        else -> "walled in: nothing replaceable to flow into"
    }

    /**
     * `/aetheris solution fill <pos> [in <liquid>] <species>=<molarity> ...`
     *
     * Authoring a cell in raw mole counts turns every recipe into an arithmetic exercise, and a
     * misleading one: the habitual "55.5 mol of water" against a cell that was a cubic metre left it 0.1%
     * full - a film that never flows. This takes the units a chemist actually writes (molarity, mol per
     * litre) and works out the rest, topping the medium up to whatever the solutes leave behind so the
     * resulting cell is exactly brim-full.
     *
     * The medium is **not** water by construction: it is whichever liquid is named, and it defaults to
     * water only because that is what almost every recipe means. What mixes with what is a property of the
     * data, not of this command - a liquid joins the phase group its own `solvent` field names, and an
     * aqueous solute joins the group *it* names, defaulting to water. Glycerol, ethylene glycol and
     * sulfuric acid all declare the water group, which is exactly why a cell of glycerol and a cell of
     * water are one phase rather than two layers, and why ions dissolve into either.
     */
    private fun fill(context: CommandContext<CommandSourceStack>, solventId: String, text: String): Int {
        val source = context.source
        val level = source.level
        val pos = BlockPosArgument.getLoadedBlockPos(context, "pos")
        val registry = AetherisCommon.chemistry.registry

        val solvent = liquid(registry, solventId) ?: throw UNKNOWN_SPECIES.create(solventId)
        // The arithmetic below tops the medium up to fill the cell, which only means anything for a bulk
        // liquid: naming an ion as the medium would be answered with a nonsense fill fraction.
        if (solvent.phase != Phase.LIQUID) throw NOT_A_LIQUID.create(solventId)
        val cell = SolutionBlockEntity.DEFAULT_VOLUME

        val solutes = LinkedHashMap<String, Double>()
        for (part in text.trim().split(WHITESPACE).filter { it.isNotEmpty() }) {
            val pieces = part.split('=')
            if (pieces.size != 2) throw BAD_SOLUTE.create(part)
            val name = pieces[0]
            val molarity = pieces[1].toDoubleOrNull() ?: throw BAD_MOLARITY.create(pieces[1])
            // One entry may contribute several species: a formula is decomposed into the ions that make it up,
            // so `H2SO4=1` is two moles of protons and one of sulfate. Entries accumulate, which is what makes
            // `HCl=1 NaOH=1` mean what it reads like.
            val contribution = contribution(registry, name) ?: throw UNKNOWN_SPECIES.create(name)
            for ((speciesId, coefficient) in contribution) {
                solutes[speciesId] = (solutes[speciesId] ?: 0.0) + molarity * coefficient * LITRES_PER_CELL
            }
        }
        // An empty list is not an error: a cell of the pure medium is exactly what you place beside another
        // one to compare their flow, which is the whole point of the viscosity work.
        if (text.isNotBlank() && solutes.isEmpty()) throw BAD_SOLUTE.create(text)

        // The solutes occupy volume of their own, so the medium fills only what they leave. That is what
        // makes the resulting fill fraction exactly one, rather than merely near it - and a fill below
        // 1/16 is precisely the state in which a solution refuses to move.
        val soluteVolume = solutes.entries.sumOf { (id, moles) ->
            moles * registry.require(id).molarVolume
        }
        val mediumMoles = ((cell - soluteVolume) / solvent.molarVolume).coerceAtLeast(0.0)

        val composition = LinkedHashMap<String, Double>()
        composition[solvent.id] = mediumMoles
        composition.putAll(solutes)

        val state = AetherisContent.SOLUTION.get().defaultBlockState().setValue(LiquidBlock.LEVEL, 0)
        level.setBlock(pos, state, Block.UPDATE_ALL)
        val entity = level.getBlockEntity(pos) as? SolutionBlockEntity
            ?: throw NOT_A_SOLUTION.create()
        entity.mixture.volume = cell
        entity.mixture.temperature = Constants.T0
        entity.mixture.replace(composition)
        entity.setChanged()
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)

        val summary = composition.entries.joinToString(" ") { (id, moles) ->
            "$id=${"%.4g".format(moles)}"
        }
        source.sendSuccess({ Component.literal("Aetheris: filled ${pos.toShortString()} -- $summary") }, true)
        return composition.size
    }

    private fun clear(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val level = source.level
        val centre = BlockPos.containing(source.position)
        val solution = AetherisContent.SOLUTION.get()
        val cursor = BlockPos.MutableBlockPos()
        var removed = 0
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    cursor.set(centre.x + dx, centre.y + dy, centre.z + dz)
                    if (!level.isLoaded(cursor)) continue
                    if (level.getBlockState(cursor).block !== solution) continue
                    level.removeBlock(cursor, false)
                    removed++
                }
            }
        }
        val count = removed
        source.sendSuccess({ Component.literal("Aetheris: removed $count solution block(s)") }, true)
        return count
    }
}
