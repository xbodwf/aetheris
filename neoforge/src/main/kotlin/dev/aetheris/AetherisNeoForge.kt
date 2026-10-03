package dev.aetheris

import dev.aetheris.client.SolutionBucketTint
import dev.aetheris.client.SolutionGogglesHud
import dev.aetheris.content.AetherisContent
import dev.aetheris.content.NeoForgeRegistrar
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
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

        // Client-only, and guarded so the client event classes are never resolved on a dedicated server.
        if (FMLEnvironment.dist.isClient) {
            // The goggle readout, layered just above the crosshair so it reads as attached to what you are
            // looking at. The drawing itself lives in common and knows nothing about NeoForge.
            eventBus.addListener { event: RegisterGuiLayersEvent ->
                event.registerAbove(
                    VanillaGuiLayers.CROSSHAIR,
                    ResourceLocation.fromNamespaceAndPath(Aetheris.MOD_ID, "goggles"),
                    LayeredDraw.Layer { graphics, _ -> SolutionGogglesHud.render(graphics) },
                )
            }

            // The bucket's two tinted layers - the rust ramp on vanilla's borrowed bucket art, and the
            // solution's own colour on ours. Same mechanism, reached through NeoForge's event.
            eventBus.addListener { event: RegisterColorHandlersEvent.Item ->
                event.register(
                    { stack, tintIndex -> SolutionBucketTint.color(stack, tintIndex) },
                    AetherisContent.SOLUTION_BUCKET.get(),
                )
            }

            // The block colour handler that used to live here is gone rather than misplaced: a fluid is
            // drawn by the fluid renderer, the block reports RenderShape.INVISIBLE, and a block colour
            // provider is therefore never consulted.
            //
            // KNOWN GAP: NeoForge will not draw this fluid at all until it has a FluidType carrying
            // IClientFluidTypeExtensions, so on this loader the solution is currently invisible and the
            // readout above has nothing to look at. Fabric is fully wired; this is the next NeoForge step.
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
