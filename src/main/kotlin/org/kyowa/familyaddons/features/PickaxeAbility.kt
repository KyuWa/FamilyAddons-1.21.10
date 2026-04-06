package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object PickaxeAbility {

    private const val CROW_COOLDOWN_MS    = 31_000L
    private const val DEFAULT_COOLDOWN_MS = 36_000L

    private var endTimeMs = 0L

    const val PREVIEW_TEXT = "§bPickobulus §f31.00s"

    fun getScale() = FamilyConfigManager.config.mining.pickobulusHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.5f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.mining
        return if (cfg.pickobulusHudX == -1 || cfg.pickobulusHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 40f).toInt()
            x to y
        } else {
            cfg.pickobulusHudX to cfg.pickobulusHudY
        }
    }

    private fun hasCrowPet(): Boolean {
        return try {
            val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return false
            tabList.any { entry ->
                val name = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: return@any false
                name.contains("crow", ignoreCase = true)
            }
        } catch (e: Exception) { false }
    }

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            if (plain == "You used your Pickobulus Pickaxe Ability!") {
                if (FamilyConfigManager.config.mining.pickobulusTimer) {
                    val cooldown = if (hasCrowPet()) CROW_COOLDOWN_MS else DEFAULT_COOLDOWN_MS
                    endTimeMs = System.currentTimeMillis() + cooldown
                }
            }
            true
        }

        HudRenderCallback.EVENT.register { context, _ ->
            if (!FamilyConfigManager.config.mining.pickobulusTimer) return@register

            val remainingMs = endTimeMs - System.currentTimeMillis()
            if (remainingMs <= 0) return@register

            val client = MinecraftClient.getInstance()
            val renderer = client.textRenderer
            val scale = getScale()
            val sw = context.scaledWindowWidth
            val sh = context.scaledWindowHeight

            val seconds = remainingMs / 1000
            val millis = (remainingMs % 1000) / 10
            val timeStr = "${seconds}.${"%02d".format(millis)}s"
            val isLow = seconds < 5

            val labelW = renderer.getWidth("Pickobulus ")
            val numColor = if (isLow) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
            val plain = "Pickobulus $timeStr"
            val tw = renderer.getWidth(plain)
            val (x, y) = resolvePos(sw, sh, scale, tw)

            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.drawText(renderer, Text.literal("§bPickobulus "), 0, 0, 0xFFFFFFFF.toInt(), true)
            context.drawText(renderer, Text.literal(timeStr), labelW, 0, numColor, true)
            matrices.popMatrix()
        }
    }
}
