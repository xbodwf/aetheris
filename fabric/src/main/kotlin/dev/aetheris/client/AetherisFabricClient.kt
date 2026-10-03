package dev.aetheris.client

import dev.aetheris.content.AetherisContent
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry

/**
 * Fabric client entrypoint, declared in `fabric.mod.json`.
 *
 * Registers the solution block's tint: the block model carries a tint index on every face, and
 * this provider reads the live composition out of the block entity, so the colour is whatever
 * the solution currently is.
 */
class AetherisFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        ColorProviderRegistry.BLOCK.register(
            { _, world, pos, tintIndex -> SolutionTint.color(world, pos, tintIndex) },
            AetherisContent.SOLUTION.get(),
        )
    }
}
