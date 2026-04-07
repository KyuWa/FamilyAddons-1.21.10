package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.util.concurrent.ConcurrentLinkedDeque

object ForagingTracker {

    private val xpEvents = ConcurrentLinkedDeque<Pair<Long, Double>>()
    private const val WINDOW_MS = 60_000L

    var isTracking = false
        private set
    private var sessionStartMs = 0L
    private var totalXpGained  = 0.0

    var xpPerHour = 0.0
        private set

    // Action bar: "+4 Foraging (30,245/50,000)"
    private val ACTION_BAR_REGEX = Regex("""\+([0-9,]+)\s+Foraging\b""")

    // Tree gift XP from hover: "+50,000 Foraging XP" or "50,000 Foraging Experience"
    private val HOVER_XP_REGEX = Regex("""([0-9,]+)\s+Foraging\s+(?:XP|Experience)""", RegexOption.IGNORE_CASE)

    var treeGiftCount = 0
        private set
    var treeGiftTotalXp = 0.0
        private set

    fun register() {
        // Action bar XP tracking
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay && isTracking) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "")
                parseActionBar(plain)
            }
            true
        }

        // Chat — Tree Gift hover XP extraction
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!overlay && isTracking) {
                val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
                if (plain.contains("Tree Gift", ignoreCase = true)) {
                    // Extract XP from hover event in the message Text
                    val xp = extractHoverXp(message)
                    if (xp != null && xp > 0) {
                        treeGiftCount++
                        treeGiftTotalXp += xp
                        addXpEvent(xp)
                        FamilyConfigManager  // just to keep import
                    } else {
                        // No hover XP found — use config average as fallback
                        val avgXp = FamilyConfigManager.config.farmCalc.treeGiftAvgXp.toDoubleOrNull() ?: 0.0
                        treeGiftCount++
                        if (avgXp > 0) {
                            treeGiftTotalXp += avgXp
                            addXpEvent(avgXp)
                        }
                    }
                }
            }
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (isTracking) updateXpPerHour()
        }
    }

    // Walk the Text tree to find hover events containing Foraging XP
    private fun extractHoverXp(text: Text): Double? {
        // Check this node's hover event
        val hover = text.style?.hoverEvent
        if (hover != null) {
            // In 1.21.11 HoverEvent stores content as a Text directly
            // Cast to get the inner text content
            try {
                val hoverContent = (hover as? net.minecraft.text.HoverEvent.ShowText)?.value
                if (hoverContent != null) {
                    val plain = collectString(hoverContent)
                    val match = HOVER_XP_REGEX.find(plain)
                    if (match != null) {
                        val xp = match.groupValues[1].replace(",", "").toDoubleOrNull()
                        if (xp != null) return xp
                    }
                }
            } catch (_: Exception) {}
        }

        // Recurse into siblings
        for (sibling in text.siblings) {
            val found = extractHoverXp(sibling)
            if (found != null) return found
        }

        return null
    }

    // Collect all string content from a Text using visit
    private fun collectString(text: Text): String {
        val sb = StringBuilder()
        text.visit { str: String -> sb.append(str); java.util.Optional.empty<Unit>() }
        return sb.toString()
    }

    private fun parseActionBar(plain: String) {
        val match = ACTION_BAR_REGEX.find(plain) ?: return
        val xp = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return
        if (xp > 0) addXpEvent(xp)
    }

    private fun addXpEvent(xp: Double) {
        val now = System.currentTimeMillis()
        xpEvents.addLast(now to xp)
        totalXpGained += xp
        val cutoff = now - WINDOW_MS
        while (xpEvents.isNotEmpty() && xpEvents.peekFirst().first < cutoff) {
            xpEvents.pollFirst()
        }
    }

    private fun updateXpPerHour() {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        while (xpEvents.isNotEmpty() && xpEvents.peekFirst().first < cutoff) {
            xpEvents.pollFirst()
        }
        if (xpEvents.size < 2) { xpPerHour = 0.0; return }
        val windowXp = xpEvents.sumOf { it.second }
        val spanMs = now - xpEvents.peekFirst().first
        if (spanMs <= 0) return
        xpPerHour = windowXp / spanMs.toDouble() * 3_600_000.0
    }

    fun start() {
        isTracking     = true
        sessionStartMs = System.currentTimeMillis()
        totalXpGained  = 0.0
        treeGiftCount  = 0
        treeGiftTotalXp = 0.0
        xpEvents.clear()
        xpPerHour = 0.0
        send("§6[FA] §aForaging XP tracker started.")
    }

    fun stop() {
        isTracking = false
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000.0
        send("§6[FA] §7Tracker stopped. Total XP: §e${formatXp(totalXpGained)} §7in §e${"%.1f".format(elapsed / 60)}m")
        if (treeGiftCount > 0) {
            send("§6[FA] §7Tree Gifts: §e$treeGiftCount §7| Bonus XP: §e${formatXp(treeGiftTotalXp)}")
        }
    }

    fun reset() {
        xpEvents.clear()
        totalXpGained   = 0.0
        treeGiftCount   = 0
        treeGiftTotalXp = 0.0
        xpPerHour       = 0.0
        sessionStartMs  = System.currentTimeMillis()
    }

    fun sessionDurationMinutes() = if (sessionStartMs == 0L) 0.0
    else (System.currentTimeMillis() - sessionStartMs) / 60_000.0

    fun totalXpGained() = totalXpGained

    private fun send(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }

    fun formatXp(xp: Double): String = when {
        xp >= 1_000_000 -> "%.2fm".format(xp / 1_000_000)
        xp >= 1_000     -> "%.1fk".format(xp / 1_000)
        else            -> "%.0f".format(xp)
    }
}


