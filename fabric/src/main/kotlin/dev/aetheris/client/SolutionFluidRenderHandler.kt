package dev.aetheris.client

import dev.aetheris.Aetheris
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.material.FluidState

/**
 * Draws a solution as a fluid, coloured by whatever is dissolved in it.
 *
 * This is where the old block colour provider's job went, and it had to move: a fluid is drawn by
 * vanilla's `FluidRenderer`, never by a block model - `LiquidBlock` reports `RenderShape.INVISIBLE`
 * precisely because of that - so a `ColorProviderRegistry.BLOCK` entry would simply never be
 * consulted.
 *
 * The move is an improvement rather than a workaround. A block colour provider is only ever asked
 * "what colour is this block", whereas the handler is given a *position*, which is exactly what
 * [SolutionTint]'s neighbour averaging needs to soften the seam between two differently coloured cells.
 */
object SolutionFluidRenderHandler : FluidRenderHandler {

    private val STILL_TEXTURE: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Aetheris.MOD_ID, "block/solution_still")

    // `block/solution_flowing` is shipped but currently unreferenced: it is the sprite this handler would
    // return for a moving surface, and a resource pack is free to point the still slot at it.

    /**
     * `[still, flowing]`, in that order, because that is the convention the renderer indexes by - but the
     * still sprite is returned for **both**.
     *
     * Vanilla chooses between the two by asking whether the fluid state is a source, and no state of ours is
     * (deliberately: see [dev.aetheris.content.AetherisFluid.Still], where not being a source is what stops
     * vanilla's own bucket from destroying a cell). Left to itself the renderer would therefore pick the
     * flowing sprite for every cell, so the choice is made here instead: a solution's surface is calm, and its
     * movement is per-cell rather than a texture animation.
     *
     * The sprites are looked up on each call rather than cached. That is one atlas lookup per fluid
     * face, and it removes any chance of holding a sprite across a resource reload, which is a classic
     * source of "the fluid turned into a checkerboard until you rejoin".
     */
    override fun getFluidSprites(
        view: BlockAndTintGetter?,
        pos: BlockPos?,
        state: FluidState?,
    ): Array<TextureAtlasSprite> {
        val still = sprite(STILL_TEXTURE)
        return arrayOf(still, still)
    }

    override fun getFluidColor(
        view: BlockAndTintGetter?,
        pos: BlockPos?,
        state: FluidState?,
    ): Int = SolutionTint.color(view, pos, 0)

    private fun sprite(texture: ResourceLocation): TextureAtlasSprite =
        Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture)
}
