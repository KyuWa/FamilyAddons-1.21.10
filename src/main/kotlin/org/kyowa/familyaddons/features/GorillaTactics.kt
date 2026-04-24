package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager

object GorillaTactics {

    // 3 second Gorilla Tactics teleport window = 60 server ticks
    private const val DURATION_TICKS = 60

    @Volatile private var remainingTicks = -1

    const val PREVIEW_TEXT = "§6Gorilla Tactics §f2.75s"

    fun getScale() = FamilyConfigManager.config.utilities.gorillaHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.5f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.utilities
        return if (cfg.gorillaHudX == -1 || cfg.gorillaHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 40f).toInt()
            x to y
        } else {
            cfg.gorillaHudX to cfg.gorillaHudY
        }
    }

    private fun isGorillaItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false

        val customData = stack.get(DataComponentTypes.CUSTOM_DATA)
        if (customData != null) {
            val nbt = customData.copyNbt()
            val id = nbt.getString("id").orElse(null)?.ifBlank { null }
                ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
            if (id == "TACTICAL_INSERTION") return true
        }

        val sb = StringBuilder()
        sb.append(stack.name.string)
        val lore = stack.get(DataComponentTypes.LORE)
        if (lore != null) {
            for (line in lore.lines) sb.append('\n').append(line.string)
        }
        val text = sb.toString().replace(COLOR_CODE_REGEX, "")
        return text.contains("Gorilla Tactics", ignoreCase = true) &&
                text.contains("RIGHT CLICK", ignoreCase = true)
    }

    fun register() {
        // Decrement once per Hypixel server tick (via CommonPingS2CPacket).
        // When the server lags, packets stop arriving and this doesn't fire,
        // so the countdown naturally pauses.
        ServerTickTracker.onTick {
            if (remainingTicks > 0) remainingTicks--
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerTickTracker.reset()
            // Don't clear the timer — Hypixel fires JOIN on every world transition
            // and the ability may still be active. Tick counter is world-agnostic.
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerTickTracker.reset()
            remainingTicks = -1
        }

        // Right-click always starts/restarts the timer, regardless of current state.
        UseItemCallback.EVENT.register { player, _, hand ->
            if (!FamilyConfigManager.config.utilities.gorillaTacticsTimer) {
                return@register ActionResult.PASS
            }
            val client = MinecraftClient.getInstance()
            if (player != client.player) return@register ActionResult.PASS
            if (hand != Hand.MAIN_HAND) return@register ActionResult.PASS

            val stack = player.getStackInHand(hand)
            if (isGorillaItem(stack)) {
                remainingTicks = DURATION_TICKS
                FamilyAddons.LOGGER.info("GorillaTactics: timer started")
            }
            ActionResult.PASS
        }

        HudRenderCallback.EVENT.register { context, _ ->
            if (!FamilyConfigManager.config.utilities.gorillaTacticsTimer) return@register
            val ticks = remainingTicks
            if (ticks <= 0) return@register

            // Smooth display: subtract fractional tick since the last server tick.
            // Freezes during lag (the tracker returns 0) instead of drifting.
            val fractional = ServerTickTracker.fractionalTicksSinceLastTick()
            val displayTicks = (ticks - fractional).coerceAtLeast(0.0)

            val client = MinecraftClient.getInstance()
            val tr = client.textRenderer
            val seconds = displayTicks * 0.05
            val text = "§6Gorilla Tactics §f${"%.2f".format(seconds)}s"
            val scale = getScale()
            val tw = tr.getWidth(text.replace(COLOR_CODE_REGEX, ""))
            val (x, y) = resolvePos(context.scaledWindowWidth, context.scaledWindowHeight, scale, tw)

            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            context.drawText(tr, Text.literal(text), 0, 0, -1, true)
            matrices.popMatrix()
        }
    }
}