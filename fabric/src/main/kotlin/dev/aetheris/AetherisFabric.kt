package dev.aetheris

import dev.aetheris.command.AetherisCommands
import dev.aetheris.content.AetherisContent
import dev.aetheris.content.FabricRegistrar
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Fabric entrypoint, declared in `fabric.mod.json`. */
class AetherisFabric : ModInitializer {
    override fun onInitialize() {
        AetherisContent.define(FabricRegistrar())

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            AetherisCommands.register(dispatcher)
        }

        // Vanilla's own defence is the empty shape in SolutionBlock, which keeps a fluid out of the
        // targeting ray so nothing can point at it. This is a backstop for the paths that do not go
        // through targeting - a mod force-breaking a block, say. Keep it until the shape mechanism has
        // been confirmed in a running game; it is one predicate and costs nothing.
        PlayerBlockBreakEvents.BEFORE.register { _, _, _, state, _ ->
            state.block !== AetherisContent.SOLUTION.get()
        }

        val engine = AetherisCommon.chemistry
        LOGGER.info(
            "{} ready: {} species, {} reactions",
            Aetheris.MOD_NAME,
            engine.registry.all.size,
            engine.reactions.size,
        )
    }

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(Aetheris.MOD_ID)
    }
}
