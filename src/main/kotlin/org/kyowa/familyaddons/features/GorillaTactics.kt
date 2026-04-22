package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object GorillaTactics {

    // 3 second Gorilla Tactics teleport window = 60 server ticks
    private const val DURATION_TICKS = 60
    private const val MS_PER_TICK = 50L

    // If the server never confirms via action bar within this many ticks, cancel the
    // tentative timer (means the ability failed — cooldown, no mana, etc.).
    private const val CONFIRM_WINDOW_TICKS = 40 // 2 seconds

    // Hypixel sends this as an overlay (action bar) when the ability actually fires.
    private val TRIGGER_REGEX = Regex("""-\d+ Mana \(Gorilla Tactics\)""")

    // Chat message when ability fails due to cooldown. The seconds value is dynamic.
    private val COOLDOWN_REGEX = Regex("""This ability is on cooldown for \d+(?:\.\d+)?s""")

    // ── State — tick-based (client ticks track server ticks on Hypixel) ──
    private var clientTick = 0
    private var endTick = -1                  // Tick at which the 3s window ends; -1 = not running
    private var tentativeStartTick = -1       // When we optimistically started from a click
    private var confirmed = false
    private var lastTickWallMs = 0L           // Wall-clock time of the most recent tick (for sub-tick smoothing)

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

    /** True if a timer is currently counting down. */
    private fun isActive(): Boolean = endTick >= 0 && clientTick < endTick

    /**
     * Returns true if the stack looks like the Tactical Insertion ability item.
     * Checks the SkyBlock NBT id first (most reliable), then falls back to lore scan
     * for any other item that might grant Gorilla Tactics in the future.
     */
    private fun isGorillaItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false

        // Primary check: SkyBlock NBT id
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA)
        if (customData != null) {
            val nbt = customData.copyNbt()
            val id = nbt.getString("id").orElse(null)?.ifBlank { null }
                ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
            if (id == "TACTICAL_INSERTION") return true
        }

        // Fallback: lore scan (future-proofs against other items gaining the ability)
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

    /** Clear all timer state. */
    private fun clearTimer() {
        endTick = -1
        tentativeStartTick = -1
        confirmed = false
    }

    fun register() {
        // ── Master tick: advance counter & handle tentative timeouts ───
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            clientTick++
            lastTickWallMs = System.currentTimeMillis()

            // Auto-cancel tentative if server never confirmed within window
            if (tentativeStartTick >= 0 && !confirmed) {
                if (clientTick - tentativeStartTick > CONFIRM_WINDOW_TICKS) {
                    clearTimer()
                }
            }

            // Auto-clear when timer expires naturally
            if (endTick >= 0 && clientTick >= endTick) {
                clearTimer()
            }
        }

        // ── Instant client-side trigger on right-click ─────────────────
        UseItemCallback.EVENT.register { player, _, hand ->
            if (!FamilyConfigManager.config.utilities.gorillaTacticsTimer) {
                return@register ActionResult.PASS
            }
            val client = MinecraftClient.getInstance()
            if (player != client.player) return@register ActionResult.PASS
            if (hand != Hand.MAIN_HAND) return@register ActionResult.PASS

            // Ignore right-clicks while a timer is already active.
            // This prevents re-triggering / double-timers when spamming the ability.
            if (isActive()) return@register ActionResult.PASS

            val stack = player.getStackInHand(hand)
            if (isGorillaItem(stack)) {
                endTick = clientTick + DURATION_TICKS
                tentativeStartTick = clientTick
                confirmed = false
            }
            ActionResult.PASS
        }

        // ── Chat / action bar listener ─────────────────────────────────
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!FamilyConfigManager.config.utilities.gorillaTacticsTimer) return@register true
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()

            // Cooldown failure — cancel tentative timer immediately
            if (COOLDOWN_REGEX.containsMatchIn(plain)) {
                if (tentativeStartTick >= 0 && !confirmed) {
                    clearTimer()
                }
                return@register true
            }

            // Success confirmation — only from overlay (action bar), not chat
            if (overlay && TRIGGER_REGEX.containsMatchIn(plain)) {
                if (tentativeStartTick >= 0 && clientTick - tentativeStartTick <= CONFIRM_WINDOW_TICKS) {
                    // Tentative timer is running — just confirm it, keep the tick alignment
                    // from the click for accurate countdown.
                    confirmed = true
                } else if (!isActive()) {
                    // No tentative timer and no active timer — fall back to starting fresh.
                    endTick = clientTick + DURATION_TICKS
                    tentativeStartTick = clientTick
                    confirmed = true
                }
                // If a timer is already running and confirmed, do nothing (don't reset).
            }
            true
        }

        // ── HUD render (tick-based with sub-tick smoothing) ───────────
        HudRenderCallback.EVENT.register { context, _ ->
            if (!FamilyConfigManager.config.utilities.gorillaTacticsTimer) return@register
            if (!isActive()) return@register

            // Compute remaining ms using ticks as the source of truth.
            // Sub-tick interpolation: clamp wall-clock delta since the last tick to [0, 50ms]
            // so lag spikes don't make the display jump (or go backwards).
            val ticksRemaining = endTick - clientTick
            val sinceTickMs = (System.currentTimeMillis() - lastTickWallMs).coerceIn(0L, MS_PER_TICK)
            val remainingMs = (ticksRemaining * MS_PER_TICK) - sinceTickMs
            if (remainingMs <= 0) return@register

            val client = MinecraftClient.getInstance()
            val tr = client.textRenderer
            val seconds = remainingMs / 1000.0
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