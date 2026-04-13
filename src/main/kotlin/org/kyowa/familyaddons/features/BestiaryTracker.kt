package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object BestiaryTracker {

    // ── Displayed values ──────────────────────────────────────────────
    var kills: Int = 0                  // total delta kills for current mob (persisted across restarts)
    var bestiaryProgress: String = "?"  // full string e.g. "13,000/20,000" or "MAX"

    // ── Internal tracking ─────────────────────────────────────────────
    private var lastRawProgress: Int = -1   // last numeric value read from tablist
    private var sessionKillDelta: Int = 0   // kills accumulated this session only
    private var lastKnownMobName: String = ""  // detect mob name changes

    // ── Session ───────────────────────────────────────────────────────
    private var sessionStartMs: Long = 0L
    private var sessionActive: Boolean = false

    // ── Fix 1: 30s idle pause ─────────────────────────────────────────
    // Uptime only counts when kills are happening.
    // If no kill in 30s, timer pauses; resumes on next kill.
    private var lastKillTimeMs: Long = 0L        // time of last detected kill
    private var pausedUptimeMs: Long = 0L        // accumulated uptime before current pause
    private var timerRunning: Boolean = false     // whether the timer is currently ticking

    // ── Tick / mouse state ────────────────────────────────────────────
    private var tickCounter = 0
    private var mouseWasDown = false
    private var resetMouseWasDown = false

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
    const val HUD_W = 180

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

        // Mouse click — mode switcher (inventory only, hover mode label)
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

        // Fix 2: Reset session button (inventory only, hover reset label)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.bestiary.enabled) return@register
            if (client.currentScreen !is InventoryScreen) { resetMouseWasDown = false; return@register }

            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (mouseDown && !resetMouseWasDown) {
                val mx = client.mouse.x / client.window.scaleFactor
                val my = client.mouse.y / client.window.scaleFactor
                if (isHoveringResetLabel(mx, my)) {
                    resetSession()
                }
            }
            resetMouseWasDown = mouseDown
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

            val m = ctx.matrices
            m.pushMatrix()
            m.translate(cfg.hudX.toFloat(), cfg.hudY.toFloat())
            m.scale(cfg.hudScale, cfg.hudScale)

            var y = 3

            // Title
            ctx.drawText(tr, "§6§l$mobName Bestiary", 4, y, -1, true)
            y += 12

            // Kills
            val killsDisplay = if (isSession) sessionKillDelta else kills
            ctx.drawText(tr, "§eKills: §f${"%,d".format(killsDisplay)}", 4, y, -1, true)
            y += 10

            // Bestiary Kills + mode tag (inventory only)
            if (isInventoryOpen) {
                val modeStr = if (isSession) "§a[Session]" else "§a[Total]"
                ctx.drawText(tr, "§eBestiary Kills: §f$bestiaryProgress  $modeStr", 4, y, -1, true)
            } else {
                ctx.drawText(tr, "§eBestiary Kills: §f$bestiaryProgress", 4, y, -1, true)
            }
            y += 10

            // Fix 1: Uptime with idle-pause logic
            if (isSession) {
                val elapsed = getActiveUptime()
                // Tint grey if timer is paused (idle), white if running
                val uptimeColor = if (timerRunning) "§f" else "§7"
                ctx.drawText(tr, "§eUptime: $uptimeColor${formatTime(elapsed)}", 4, y, -1, true)
                y += 10
            }

            // Fix 2: Reset session button (only when inventory open)
            if (isInventoryOpen && isSession) {
                ctx.drawText(tr, "§c[Reset Session]", 4, y, -1, true)
            }

            m.popMatrix()

            // Tooltips when inventory open
            if (isInventoryOpen) {
                val mx = client.mouse.x / client.window.scaleFactor
                val my = client.mouse.y / client.window.scaleFactor
                if (isHoveringModeLabel(mx, my)) {
                    renderModeTooltip(ctx, mx.toInt(), my.toInt(), isSession)
                } else if (isSession && isHoveringResetLabel(mx, my)) {
                    renderResetTooltip(ctx, mx.toInt(), my.toInt())
                }
            }
        }
    }

    // ── Fix 1: Active uptime calculation ──────────────────────────────
    // Timer only counts while kills are happening. Pauses after 30s idle.
    private fun getActiveUptime(): Long {
        if (!sessionActive) return 0L
        val now = System.currentTimeMillis()

        return if (timerRunning) {
            // Check if 30s have elapsed since last kill
            if (now - lastKillTimeMs > 30_000L) {
                // Pause the timer
                pausedUptimeMs += (lastKillTimeMs + 30_000L) - sessionStartMs - (if (pausedUptimeMs > 0) pausedUptimeMs else 0L)
                // Simpler: just add to paused bucket the period that was active
                timerRunning = false
                pausedUptimeMs
            } else {
                pausedUptimeMs + (now - lastKillTimeMs.coerceAtLeast(sessionStartMs))
            }
        } else {
            pausedUptimeMs
        }
    }

    // ── Hover regions ─────────────────────────────────────────────────
    // Mode label: "Bestiary Kills" line = y-offset 25 from HUD top
    private fun isHoveringModeLabel(mx: Double, my: Double): Boolean {
        val cfg = FamilyConfigManager.config.bestiary
        val sc = cfg.hudScale.toDouble()
        val sx = cfg.hudX.toDouble()
        val sy = cfg.hudY.toDouble()
        val lineTopY    = sy + 25 * sc
        val lineBottomY = lineTopY + 10 * sc
        return mx >= sx && mx <= sx + HUD_W * sc && my >= lineTopY && my <= lineBottomY
    }

    // Reset label: appears after uptime line = y-offset 35 (session mode only)
    private fun isHoveringResetLabel(mx: Double, my: Double): Boolean {
        val cfg = FamilyConfigManager.config.bestiary
        val sc = cfg.hudScale.toDouble()
        val sx = cfg.hudX.toDouble()
        val sy = cfg.hudY.toDouble()
        // title(12) + kills(10) + bestiaryKills(10) + uptime(10) + padding(3) = y=45
        val lineTopY    = sy + 45 * sc
        val lineBottomY = lineTopY + 10 * sc
        return mx >= sx && mx <= sx + HUD_W * sc && my >= lineTopY && my <= lineBottomY
    }

    // ── Tooltips ──────────────────────────────────────────────────────
    private fun renderModeTooltip(ctx: net.minecraft.client.gui.DrawContext, mx: Int, my: Int, isSession: Boolean) {
        val tr = MinecraftClient.getInstance().textRenderer
        val lines = listOf(
            "§eDisplay Mode",
            "",
            if (!isSession) "§a▶ Total" else "§7  Total",
            if (isSession)  "§a▶ Session" else "§7  Session",
            "",
            "§bClick to switch Display Mode!"
        )
        renderTooltip(ctx, tr, lines, mx, my)
    }

    private fun renderResetTooltip(ctx: net.minecraft.client.gui.DrawContext, mx: Int, my: Int) {
        val tr = MinecraftClient.getInstance().textRenderer
        val lines = listOf(
            "§cReset Session",
            "",
            "§7Resets session kills and uptime.",
            "§7Total kills are kept.",
            "",
            "§bClick to reset!"
        )
        renderTooltip(ctx, tr, lines, mx, my)
    }

    private fun renderTooltip(
        ctx: net.minecraft.client.gui.DrawContext,
        tr: net.minecraft.client.font.TextRenderer,
        lines: List<String>,
        mx: Int, my: Int
    ) {
        val maxW = lines.maxOf { tr.getWidth(it.replace(COLOR_CODE_REGEX, "")) }
        val ttW  = maxW + 12
        val ttH  = lines.size * 10 + 6

        var tx = mx + 10
        var ty = my - ttH - 4
        val sw = ctx.scaledWindowWidth
        if (tx + ttW > sw) tx = mx - ttW - 4
        if (ty < 0) ty = my + 14

        ctx.fill(tx - 1, ty - 1, tx + ttW + 1, ty + ttH + 1, 0xFF1E0030.toInt())
        ctx.fill(tx,     ty,     tx + ttW,     ty + ttH,     0xF0100010.toInt())

        var lineY = ty + 3
        for (line in lines) {
            if (line.isNotEmpty()) ctx.drawText(tr, line, tx + 6, lineY, -1, true)
            lineY += 10
        }
    }

    // ── Session management ────────────────────────────────────────────
    private fun startSession() {
        sessionStartMs  = System.currentTimeMillis()
        sessionKillDelta = 0
        sessionActive   = true
        timerRunning    = false
        pausedUptimeMs  = 0L
        lastKillTimeMs  = 0L
    }

    // Fix 2: Reset session only (keep total)
    private fun resetSession() {
        sessionKillDelta = 0
        sessionStartMs   = System.currentTimeMillis()
        timerRunning     = false
        pausedUptimeMs   = 0L
        lastKillTimeMs   = 0L
        // sessionActive stays true if we're in session mode
    }

    // ── Tablist parser ────────────────────────────────────────────────
    private fun parseTablist(client: MinecraftClient) {
        val tabList = client.networkHandler?.playerList ?: return
        val cfg     = FamilyConfigManager.config.bestiary
        val target  = cfg.mobName.trim().lowercase()

        // Fix 3: Detect mob name change → reset total + session, load saved kills
        if (target != lastKnownMobName) {
            // Save current kills for old mob
            if (lastKnownMobName.isNotEmpty()) {
                cfg.savedKills[lastKnownMobName] = kills
                FamilyConfigManager.save()
            }
            // Load saved kills for new mob
            kills             = cfg.savedKills[target] ?: 0
            lastKnownMobName  = target
            lastRawProgress   = -1
            bestiaryProgress  = "?"
            // Reset session
            sessionKillDelta  = 0
            sessionActive     = false
            timerRunning      = false
            pausedUptimeMs    = 0L
            lastKillTimeMs    = 0L
        }

        val sorted = tabList
            .filter { it.displayName != null }
            .sortedBy { it.profile.name ?: "" }

        var inBestiary = false

        for (entry in sorted) {
            val raw       = entry.displayName!!.string
            val stripped  = raw.replace(COLOR_CODE_REGEX, "")
            val clean     = stripped.trim()
            val isIndented = stripped.startsWith(" ")

            if (clean == "Bestiary:") { inBestiary = true; continue }

            if (clean.isNotEmpty() && !isIndented && clean.endsWith(":")) {
                if (inBestiary) inBestiary = false
                continue
            }

            if (!inBestiary || !isIndented) continue

            val trimmed = stripped.trimStart()
            if (!trimmed.lowercase().startsWith(target)) continue

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
                                // First read — baseline only
                                lastRawProgress = num
                            }
                            num > lastRawProgress -> {
                                val delta = num - lastRawProgress
                                kills += delta
                                if (sessionActive) sessionKillDelta += delta

                                // Fix 1: Record kill time, start/resume timer
                                val now = System.currentTimeMillis()
                                if (!timerRunning) {
                                    // Resume timer: shift sessionStartMs so pausedUptimeMs remains correct
                                    if (sessionActive) {
                                        // We were paused — bump sessionStartMs forward to account for idle gap
                                        val idleGap = now - (lastKillTimeMs.takeIf { it > 0L } ?: now)
                                        sessionStartMs += idleGap
                                    }
                                    timerRunning = true
                                }
                                lastKillTimeMs = now

                                // Persist total kills for this mob
                                cfg.savedKills[target] = kills
                                FamilyConfigManager.save()

                                lastRawProgress = num

                                if (cfg.displayMode == 1 && !sessionActive) startSession()
                            }
                            num < lastRawProgress -> {
                                lastRawProgress = num
                            }
                        }
                    }
                }
            }
            break
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