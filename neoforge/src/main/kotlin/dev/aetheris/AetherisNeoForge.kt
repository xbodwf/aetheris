package dev.aetheris

import dev.aetheris.client.SolutionTint
import dev.aetheris.content.AetherisContent
import dev.aetheris.content.NeoForgeRegistrar
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** NeoForge entrypoint, discovered through the `@Mod` annotation. */
@Mod(Aetheris.MOD_ID)
class AetherisNeoForge(
    eventBus: IEventBus,
    modContainer: ModContainer,
) {
    init {
        AetherisContent.define(NeoForgeRegistrar(eventBus))

        // Client-only: tint the solution block from its live composition. Guarded so the
        // client event class is never resolved on a dedicated server.
        if (FMLEnvironment.dist.isClient) {
            eventBus.addListener { event: RegisterColorHandlersEvent.Block ->
                event.register(
                    { _, world, pos, tint -> SolutionTint.color(world, pos, tint) },
                    AetherisContent.SOLUTION.get(),
                )
            }
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
