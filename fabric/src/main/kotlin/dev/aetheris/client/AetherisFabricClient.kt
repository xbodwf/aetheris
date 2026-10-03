package dev.aetheris.client

import dev.aetheris.content.AetherisContent
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.color.item.ItemColor
import net.minecraft.client.renderer.RenderType

/**
 * Fabric client entrypoint, declared in `fabric.mod.json`.
 *
 * A solution is a fluid, so it is drawn the way vanilla draws fluids: the block model is
 * `RenderShape.INVISIBLE` and the fluid renderer does the work. That means two registrations rather
 * than one - the handler that supplies the sprites and the per-position colour, and the render layer
 * that says the surface is translucent.
 *
 * There is deliberately no `ColorProviderRegistry.BLOCK` entry here. It would never be consulted,
 * because nothing about this block goes through block model rendering any more; the colour comes from
 * [SolutionFluidRenderHandler.getFluidColor] instead.
 *
 * Both hooks are thin: the fluid handler is a loader-shaped adapter, and the goggle readout is entirely
 * [SolutionGogglesHud], which knows nothing about Fabric so NeoForge can call the same thing.
 */
class AetherisFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        FluidRenderHandlerRegistry.INSTANCE.register(
            AetherisContent.SOLUTION_STILL,
            AetherisContent.SOLUTION_FLOWING,
            SolutionFluidRenderHandler,
        )
        // Translucent: the textures carry alpha, and an opaque layer would throw that away and make the
        // liquid look like painted glass.
        BlockRenderLayerMap.INSTANCE.putFluid(AetherisContent.SOLUTION_STILL, RenderType.translucent())
        BlockRenderLayerMap.INSTANCE.putFluid(AetherisContent.SOLUTION_FLOWING, RenderType.translucent())
        // The fluid renderer only draws where the block itself would have drawn nothing, so the block
        // has to be declared transparent for the two to compose.
        FluidRenderHandlerRegistry.INSTANCE.setBlockTransparency(AetherisContent.SOLUTION.get(), true)

        // The bucket's two tinted layers: the rust ramp on vanilla's own bucket art, and the solution's
        // colour on our liquid shape. Item tints are the same mechanism vanilla dyes leather armour with.
        ColorProviderRegistry.ITEM.register(
            ItemColor { stack, tintIndex -> SolutionBucketTint.color(stack, tintIndex) },
            AetherisContent.SOLUTION_BUCKET.get(),
        )

        // The goggles readout. Registered unconditionally; the overlay itself decides whether a pair is
        // being worn and whether a solution is in view, so there is nothing to switch on or off here.
        HudRenderCallback.EVENT.register { graphics, _ -> SolutionGogglesHud.render(graphics) }
    }
}
