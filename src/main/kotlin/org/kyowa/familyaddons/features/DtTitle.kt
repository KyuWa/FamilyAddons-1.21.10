package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.config.FamilyConfigManager

object DtTitle {

    private const val FADE_IN  = 10
    private const val HOLD     = 40
    private const val FADE_OUT = 10
    private const val TOTAL    = FADE_IN + HOLD + FADE_OUT

    private var titleText: String? = null
    private var titleTicks = 0

    // Preview text shown in /fagui
    const val PREVIEW_TEXT = "§e[MVP++] Player §crequested §fDT!"

    fun show(text: String) {
        if (!FamilyConfigManager.config.kuudra.dtTitle) return
        titleText = text
        titleTicks = TOTAL
    }

    fun tick() {
        if (titleTicks > 0) titleTicks--
    }

    fun getScale() = FamilyConfigManager.config.kuudra.dtTitleScale.toFloatOrNull()?.coerceAtLeast(1f) ?: 2f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        return if (FamilyConfigManager.config.kuudra.dtTitleHudX == -1 || FamilyConfigManager.config.kuudra.dtTitleHudY == -1) {
            // Auto-center in screen coords
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f - 20f * scale).toInt()
            x to y
        } else {
            // Saved as raw screen pixels
            FamilyConfigManager.config.kuudra.dtTitleHudX to FamilyConfigManager.config.kuudra.dtTitleHudY
        }
    }

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            val text = titleText ?: return@register
            if (titleTicks <= 0) return@register
            if (!FamilyConfigManager.config.kuudra.dtTitle) return@register

            val elapsed = TOTAL - titleTicks
            val alpha = when {
                elapsed < FADE_IN -> ((elapsed.toFloat() / FADE_IN) * 255).toInt()
                elapsed < FADE_IN + HOLD -> 255
                else -> ((1f - (elapsed - FADE_IN - HOLD).toFloat() / FADE_OUT) * 255).toInt()
            }.coerceIn(0, 255)

            if (alpha == 0) return@register

            val client = MinecraftClient.getInstance()
            val renderer = client.textRenderer
            val scale = getScale()
            val sw = context.scaledWindowWidth
            val sh = context.scaledWindowHeight
            val plain = text.replace(COLOR_CODE_REGEX, "")
            val tw = renderer.getWidth(plain)
            val (x, y) = resolvePos(sw, sh, scale, tw)
            val color = (alpha shl 24) or 0xFFFFFF

            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.drawText(renderer, Text.literal(text), 0, 0, color, true)
            matrices.popMatrix()
        }
    }
}
