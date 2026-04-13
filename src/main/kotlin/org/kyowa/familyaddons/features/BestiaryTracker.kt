package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object BestiaryTracker {

    // ── Displayed values ──────────────────────────────────────────────
    var kills: Int = 0                  // total delta kills accumulated since tracking started
    var bestiaryProgress: String = "?"  // full string e.g. "13,000/20,000" or "MAX"

    // ── Internal tracking ─────────────────────────────────────────────
    private var lastRawProgress: Int = -1   // last numeric value read from tablist (-1 = not yet read)
    private var sessionKillDelta: Int = 0   // kills accumulated this session only

    // ── Session ───────────────────────────────────────────────────────
    private var sessionStartMs: Long = 0L
    private var sessionActive: Boolean = false

    // ── Tick / mouse state ────────────────────────────────────────────
    private var tickCounter = 0
    private var mouseWasDown = false

    // ── HUD proxy to config ───────────────────────────────────────────
    var hudX: Int
        get() = FamilyConfigManager.config.bestiary.hudX
        set(v) { FamilyConfigManager.config.bestiary.hudX = v }
    var hudY: Int
        get() = FamilyConfigManager.config.bestiary.hudY
        set(v) { FamilyConfigManager.config.bestiary.hudY = v }
    var hudScale: Float
        get() = FamilyConfigManager.config.bestiary.hudScale
        set(v) { FamilyConfigManager.config.bestiary.hudScale = v }

    fun save() = FamilyConfigManager.save()

    // ── HUD unscaled dimensions ───────────────────────────────────────
    const val HUD_W = 170

    fun hudH(): Int {
        val cfg = FamilyConfigManager.config.bestiary
        // title(12) + kills(10) + bestiary kills(10) + padding(6)
        var h = 38
        if (cfg.displayMode == 1) h += 10  // uptime line
        return h
    }

    // ── Register ──────────────────────────────────────────────────────
    fun register() {
        // Poll tablist every 60 ticks (~3 sec)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.bestiary.enabled) return@register
            if (tickCounter++ % 60 != 0) return@register
            parseTablist(client)
        }

        // Mouse click — only fires when inventory open AND hovering the mode label
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.bestiary.enabled) return@register
            if (client.currentScreen !is InventoryScreen) { mouseWasDown = false; return@register }

            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (mouseDown && !mouseWasDown) {
                val mx = client.mouse.x / client.window.scaleFactor
                val my = client.mouse.y / client.window.scaleFactor
                if (isHoveringModeLabel(mx, my)) {
                    val cfg = FamilyConfigManager.config.bestiary
                    cfg.displayMode = if (cfg.displayMode == 0) 1 else 0
                    if (cfg.displayMode == 1 && !sessionActive) startSession()
                    FamilyConfigManager.save()
                }
            }
            mouseWasDown = mouseDown
        }

        // HUD render
        HudRenderCallback.EVENT.register { ctx, _ ->
            val cfg = FamilyConfigManager.config.bestiary
            if (!cfg.enabled) return@register
            if (cfg.mobName.isBlank()) return@register
            val client = MinecraftClient.getInstance()
            val tr = client.textRenderer
            val isSession = cfg.displayMode == 1
            val mobName = cfg.mobName.ifBlank { "?" }
            val isInventoryOpen = client.currentScreen is InventoryScreen

            val w = HUD_W
            val h = hudH()

            val m = ctx.matrices
            m.pushMatrix()
            m.translate(cfg.hudX.toFloat(), cfg.hudY.toFloat())
            m.scale(cfg.hudScale, cfg.hudScale)

            // Background

            var y = 3

            // Title
            ctx.drawText(tr, "§6§l$mobName Bestiary", 4, y, -1, true)
            y += 12

            // Kills — session delta if session mode, total delta if total mode
            val killsDisplay = if (isSession) sessionKillDelta else kills
            ctx.drawText(tr, "§eKills: §f${"%,d".format(killsDisplay)}", 4, y, -1, true)
            y += 10

            // Bestiary Kills — append mode label when inventory open (hoverable)
            if (isInventoryOpen) {
                val modeStr = if (isSession) "§a[Session]" else "§a[Total]"
                ctx.drawText(tr, "§eBestiary Kills: §f$bestiaryProgress  $modeStr", 4, y, -1, true)
            } else {
                ctx.drawText(tr, "§eBestiary Kills: §f$bestiaryProgress", 4, y, -1, true)
            }
            y += 10

            // Uptime — only in session mode
            if (isSession) {
                val elapsed = if (sessionActive) System.currentTimeMillis() - sessionStartMs else 0L
                ctx.drawText(tr, "§eUptime: §f${formatTime(elapsed)}", 4, y, -1, true)
            }

            m.popMatrix()

            // Tooltip — show when inventory open and hovering the mode label
            if (isInventoryOpen) {
                val mx = client.mouse.x / client.window.scaleFactor
                val my = client.mouse.y / client.window.scaleFactor
                if (isHoveringModeLabel(mx, my)) {
                    renderModeTooltip(ctx, mx.toInt(), my.toInt(), isSession)
                }
            }
        }
    }

    // ── Check if mouse is over the mode label on the Bestiary Kills line ──
    // That line is at y-offset: padding(3) + title(12) + kills(10) = 25 from HUD top
    private fun isHoveringModeLabel(mx: Double, my: Double): Boolean {
        val cfg = FamilyConfigManager.config.bestiary
        val sc = cfg.hudScale.toDouble()
        val sx = cfg.hudX.toDouble()
        val sy = cfg.hudY.toDouble()

        // The [Total]/[Session] tag is at the end of the "Bestiary Kills" line
        // We make the whole line clickable for simplicity
        val lineTopY = sy + 25 * sc
        val lineBottomY = lineTopY + 10 * sc
        val lineLeft = sx
        val lineRight = sx + HUD_W * sc

        return mx >= lineLeft && mx <= lineRight && my >= lineTopY && my <= lineBottomY
    }

    // ── Render the SkyHanni-style dropdown tooltip ────────────────────
    private fun renderModeTooltip(
        ctx: net.minecraft.client.gui.DrawContext,
        mx: Int, my: Int,
        isSession: Boolean
    ) {
        val tr = MinecraftClient.getInstance().textRenderer
        val lines = listOf(
            "§eDisplay Mode",
            "",
            if (!isSession) "§a▶ Total" else "§7  Total",
            if (isSession)  "§a▶ Session" else "§7  Session",
            "",
            "§bClick to switch Display Mode!"
        )

        val maxW = lines.maxOf { tr.getWidth(it.replace(COLOR_CODE_REGEX, "")) }
        val ttW = maxW + 12
        val ttH = lines.size * 10 + 6

        // Position tooltip above/right of cursor, clamp to screen
        var tx = mx + 10
        var ty = my - ttH - 4
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        if (tx + ttW > sw) tx = mx - ttW - 4
        if (ty < 0) ty = my + 14

        // Border + background (dark purple like SkyHanni)
        ctx.fill(tx - 1, ty - 1, tx + ttW + 1, ty + ttH + 1, 0xFF1E0030.toInt())
        ctx.fill(tx,     ty,     tx + ttW,     ty + ttH,     0xF0100010.toInt())

        var lineY = ty + 3
        for (line in lines) {
            if (line.isNotEmpty()) ctx.drawText(tr, line, tx + 6, lineY, -1, true)
            lineY += 10
        }
    }

    // ── Session ───────────────────────────────────────────────────────
    private fun startSession() {
        sessionStartMs = System.currentTimeMillis()
        sessionKillDelta = 0
        sessionActive = true
    }

    // Tablist entries arrive unordered. Sort by profile name (!A-a, !B-c, !D-m...)
    // so section headers appear before their entries.
    // Format after sorting:
    //   "Bestiary:"              ← no leading space
    //   " Glacite Walker 10: 1,255/1,500"  ← leading space = entry
    //   " Ghost 12: 13,000/20,000"
    private fun parseTablist(client: MinecraftClient) {
        val tabList = client.networkHandler?.playerList ?: return
        val cfg = FamilyConfigManager.config.bestiary
        val target = cfg.mobName.trim().lowercase()

        // Sort by profile name so tab columns are in the right order
        val sorted = tabList
            .filter { it.displayName != null }
            .sortedBy { it.profile.name ?: "" }

        var inBestiary = false

        for (entry in sorted) {
            val raw = entry.displayName!!.string
            val stripped = raw.replace(COLOR_CODE_REGEX, "")  // keep leading space intact
            val clean = stripped.trim()
            val isIndented = stripped.startsWith(" ")

            // Detect Bestiary section header
            if (clean == "Bestiary:") {
                inBestiary = true
                continue
            }

            // Any non-indented "Xyz:" line ends the current section
            if (clean.isNotEmpty() && !isIndented && clean.endsWith(":")) {
                if (inBestiary) inBestiary = false
                continue
            }

            if (!inBestiary || !isIndented) continue

            // Entry: " Glacite Walker 10: 1,255/1,500"
            val trimmed = stripped.trimStart()
            if (!trimmed.lowercase().startsWith(target)) continue

            // Value is everything after the last ':'
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx < 0) continue
            val value = trimmed.substring(colonIdx + 1).trim()
            if (value.isEmpty()) continue

            bestiaryProgress = value

            when {
                value.equals("MAX", ignoreCase = true) -> { /* no numeric update */ }
                value.contains("/") -> {
                    val num = value.substringBefore("/").replace(",", "").trim().toIntOrNull()
                    if (num != null) {
                        when {
                            lastRawProgress < 0 -> {
                                // First read — set baseline, no kills counted yet
                                lastRawProgress = num
                            }
                            num > lastRawProgress -> {
                                val delta = num - lastRawProgress
                                kills += delta
                                if (sessionActive) sessionKillDelta += delta
                                lastRawProgress = num
                            }
                            num < lastRawProgress -> {
                                // Mob changed or tracker reset — new baseline
                                lastRawProgress = num
                            }
                            // num == lastRawProgress: no change
                        }
                        if (cfg.displayMode == 1 && !sessionActive) startSession()
                    }
                }
            }
            break // found our mob, stop
        }
    }

    // ── Time formatter ────────────────────────────────────────────────
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
    }
}