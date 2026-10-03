package dev.aetheris.client

import dev.aetheris.content.AetherisContent
import dev.aetheris.content.ChemistsGogglesItem
import dev.aetheris.content.SolutionBlockEntity
import dev.aetheris.content.SolutionReadoutLines
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.SolutionNames
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult

/**
 * The goggle readout: with a pair on your head, whatever solution you are looking at reports what is in
 * it.
 *
 * Written against vanilla types only, with no loader API in sight, so both loaders can call it from their
 * own HUD hook. That is also why it is one function taking a [GuiGraphics] rather than a callback
 * implementation: the callback shape differs per loader, the drawing does not.
 *
 * Two things make it possible, and the first is not obvious.
 *
 * **A solution cannot be looked at the normal way.** It has an empty block shape - precisely what keeps it
 * out of the targeting ray and therefore unbreakable - so the crosshair passes straight through it. The
 * fix is to ask a *fluid-aware* raycast instead: `ClipContext` with `Fluid.ANY` tests the fluid shape,
 * which is real and comes from the mixture through `getOwnHeight`. Aiming at the liquid therefore sees it,
 * while a glance that skims the air above a shallow puddle still goes through - which is the behaviour you
 * want anyway.
 *
 * **The numbers are already the engine's job.** [SolutionNames] decides what counts as a solute and what
 * counts as the medium; this file only formats and translates.
 */
object SolutionGogglesHud {

    /** Beyond this many contents the panel stops being something you read and starts being a wall. */
    private const val MAX_LINES = 4

    private const val WHITE = 0xFFFFFF
    private const val DIM = 0x707070

    /**
     * The panel's own backdrop.
     *
     * Drawn explicitly rather than asked for, because the text helpers that offer one draw it per line and
     * at a width they choose, which produced a readout with no visible panel at all. A single rectangle
     * behind the whole block of text is also the only way to get one continuous background.
     */
    private const val PANEL_COLOR = 0xB0101010.toInt()
    private const val PANEL_PADDING = 4

    fun render(graphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui) return
        val player = minecraft.player ?: return
        if (!ChemistsGogglesItem.isWornBy(player)) return
        val level = minecraft.level ?: return
        val entity = target(level, player) ?: return
        render(graphics, minecraft, entity)
    }

    /** The solution under the crosshair, found through its fluid shape rather than its block shape. */
    private fun target(level: Level, player: LocalPlayer): SolutionBlockEntity? {
        val eye = player.getEyePosition(1.0f)
        val end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()))
        val hit = level.clip(
            ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player),
        )
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = hit.blockPos
        if (level.getBlockState(pos).block !== AetherisContent.SOLUTION.get()) return null
        return level.getBlockEntity(pos) as? SolutionBlockEntity
    }

    private fun render(graphics: GuiGraphics, minecraft: Minecraft, entity: SolutionBlockEntity) {
        val mixture = entity.mixture
        val font = minecraft.font

        // The contents are formatted by the same code a container's tooltip uses, so what the goggles show
        // and what the bucket in your hand says cannot drift apart.
        val lines = SolutionReadoutLines.of(mixture, MAX_LINES)
        val footer = Component.translatable(
            "aetheris.goggles.footer",
            "%.1f".format(mixture.temperature),
            "%.0f".format(entity.fill() * 100.0),
        )

        val header = Component.translatable("block.aetheris.solution")
        val widths = lines.map { font.width(it) } + font.width(footer) + font.width(header)
        val panelWidth = widths.max() + PANEL_PADDING * 2
        val lineHeight = font.lineHeight + 2
        val panelHeight = lineHeight * (lines.size + 2) + PANEL_PADDING * 2
        val x = graphics.guiWidth() / 2 + 14
        val y = graphics.guiHeight() / 2 + 4

        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_COLOR)
        var cursorY = y + PANEL_PADDING
        graphics.drawString(font, header, x + PANEL_PADDING, cursorY, WHITE, true)
        cursorY += lineHeight
        for (line in lines) {
            graphics.drawString(font, line, x + PANEL_PADDING, cursorY, WHITE, true)
            cursorY += lineHeight
        }
        graphics.drawString(font, footer, x + PANEL_PADDING, cursorY, DIM, true)
    }
}
