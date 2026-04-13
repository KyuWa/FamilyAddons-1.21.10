package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.text.Text
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

class HudEditorScreen : Screen(Text.literal("FA HUD Editor")) {

    data class HudElement(
        val id: String,
        val label: String,
        var x: Int,
        var y: Int,
        var w: Int,
        var h: Int,
        var scale: Float = 1f,
        var dragging: Boolean = false,
        var dragOffX: Double = 0.0,
        var dragOffY: Double = 0.0,
        val canScale: Boolean = false,
        val onSave: (HudElement) -> Unit,
        val renderContent: (context: DrawContext, elem: HudElement) -> Unit
    ) {
        val screenW get() = (w * scale).toInt()
        val screenH get() = (h * scale).toInt()
    }

    private val elements = mutableListOf<HudElement>()
    private var activeElement: HudElement? = null

    override fun init() {
        elements.clear()
        val client = MinecraftClient.getInstance()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight
        val tr = client.textRenderer

        // Parkour Timer
        elements.add(HudElement(
            id = "parkourTimer", label = "Parkour Timer",
            x = Parkour.hudX, y = Parkour.hudY,
            w = 160, h = 12,
            scale = Parkour.hudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.hudX = elem.x
                Parkour.hudY = elem.y
                Parkour.hudScale = elem.scale
                Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.drawText(tr, "§f0:12.345  §7CP §f2§7/§f5", 0, 0, -1, true)
            }
        ))

        // Parkour Arrow
        elements.add(HudElement(
            id = "parkourArrow", label = "Parkour Arrow",
            x = Parkour.arrowHudX, y = Parkour.arrowHudY,
            w = 40, h = 22,
            scale = Parkour.arrowHudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.arrowHudX = elem.x
                Parkour.arrowHudY = elem.y
                Parkour.arrowHudScale = elem.scale
                Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.drawText(tr, "§e↑", 0, 0, -1, true)
                ctx.drawText(tr, "§f12m", 0, 12, -1, true)
            }
        ))

        // Parkour Checkpoint Notification
        elements.add(HudElement(
            id = "parkourCpNotif", label = "Parkour Checkpoint",
            x = Parkour.cpHudX, y = Parkour.cpHudY,
            w = 130, h = 12,
            scale = Parkour.cpHudScale,
            canScale = true,
            onSave = { elem ->
                Parkour.cpHudX = elem.x
                Parkour.cpHudY = elem.y
                Parkour.cpHudScale = elem.scale
                Parkour.save()
            },
            renderContent = { ctx, _ ->
                ctx.drawText(tr, "§e§lCheckpoint 2/5", 0, 0, -1, true)
            }
        ))

        // Infernal Key Tracker
        val ktW = 120
        val ktH = InfernalKeyTracker.getLineCount() * 10 + 4
        elements.add(HudElement(
            id = "keyTracker", label = "Key Tracker",
            x = FamilyConfigManager.config.kuudra.keyTrackerHudX,
            y = FamilyConfigManager.config.kuudra.keyTrackerHudY,
            w = ktW, h = ktH,
            canScale = false,
            onSave = { elem ->
                FamilyConfigManager.config.kuudra.keyTrackerHudX = elem.x
                FamilyConfigManager.config.kuudra.keyTrackerHudY = elem.y
            },
            renderContent = { ctx, _ ->
                InfernalKeyTracker.renderLines(ctx)
            }
        ))

        // ── Bestiary Tracker ──────────────────────────────────────────────
        elements.add(HudElement(
            id = "bestiaryTracker", label = "Bestiary Tracker",
            x = BestiaryTracker.hudX, y = BestiaryTracker.hudY,
            w = BestiaryTracker.HUD_W, h = BestiaryTracker.hudH(),
            scale = BestiaryTracker.hudScale,
            canScale = true,
            onSave = { elem ->
                BestiaryTracker.hudX = elem.x
                BestiaryTracker.hudY = elem.y
                BestiaryTracker.hudScale = elem.scale
                BestiaryTracker.save()
            },
            renderContent = { ctx, _ ->
                val mobName = FamilyConfigManager.config.bestiary.mobName.ifBlank { "?" }
                val isSession = FamilyConfigManager.config.bestiary.displayMode == 1
                var ry = 3
                ctx.drawText(tr, "§6§l$mobName Bestiary", 4, ry, -1, true); ry += 12
                ctx.drawText(tr, "§eKills: §f${"%,d".format(BestiaryTracker.kills)}", 4, ry, -1, true); ry += 10
                ctx.drawText(tr, "§eBestiary Kills: §f${BestiaryTracker.bestiaryProgress}", 4, ry, -1, true); ry += 10
                if (isSession) ctx.drawText(tr, "§eUptime: §f0m 0s", 4, ry, -1, true)
            }
        ))

        // Pickobulus Timer
        val pbScale = PickaxeAbility.getScale()
        val pbPlain = PickaxeAbility.PREVIEW_TEXT.replace(COLOR_CODE_REGEX, "")
        val pbW = tr.getWidth(pbPlain)
        val pbH = 10
        val pbX = if (FamilyConfigManager.config.mining.pickobulusHudX == -1)
            ((sw - pbW * pbScale) / 2f).toInt()
        else FamilyConfigManager.config.mining.pickobulusHudX
        val pbY = if (FamilyConfigManager.config.mining.pickobulusHudY == -1)
            (sh / 2f + 40f).toInt()
        else FamilyConfigManager.config.mining.pickobulusHudY

        elements.add(HudElement(
            id = "pickobulusTimer", label = "Pickobulus Timer",
            x = pbX, y = pbY,
            w = pbW + 4, h = pbH,
            scale = pbScale,
            canScale = true,
            onSave = { elem ->
                FamilyConfigManager.config.mining.pickobulusHudX = elem.x
                FamilyConfigManager.config.mining.pickobulusHudY = elem.y
                FamilyConfigManager.config.mining.pickobulusHudScale = "%.1f".format(elem.scale)
            },
            renderContent = { ctx, _ ->
                val labelW = tr.getWidth("Pickobulus ")
                ctx.drawText(tr, Text.literal("§bPickobulus "), 0, 0, 0xFFFFFFFF.toInt(), true)
                ctx.drawText(tr, Text.literal("31s"), labelW, 0, 0xFFFFFFFF.toInt(), true)
            }
        ))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x88000000.toInt())

        val tr = MinecraftClient.getInstance().textRenderer
        val hint = "§7Drag to move  |  §eScroll §7to scale  |  §eEsc §7to save"
        val hintW = tr.getWidth(hint.replace(COLOR_CODE_REGEX, ""))
        context.drawText(tr, hint, (width - hintW) / 2, 8, -1, true)

        for (elem in elements) {
            if (elem.dragging) {
                elem.x = (mouseX - elem.dragOffX).toInt()
                elem.y = (mouseY - elem.dragOffY).toInt()
            }

            val sw = elem.screenW
            val sh = elem.screenH
            val isActive = elem == activeElement

            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(elem.x.toFloat(), elem.y.toFloat())
            matrices.scale(elem.scale, elem.scale)
            elem.renderContent(context, elem)
            matrices.popMatrix()

            val border = if (isActive) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
            context.fill(elem.x - 1,  elem.y - 1,  elem.x + sw + 1, elem.y,          border)
            context.fill(elem.x - 1,  elem.y + sh, elem.x + sw + 1, elem.y + sh + 1, border)
            context.fill(elem.x - 1,  elem.y,      elem.x,          elem.y + sh,      border)
            context.fill(elem.x + sw, elem.y,      elem.x + sw + 1, elem.y + sh,      border)

            if (elem.canScale) {
                val scaleStr = "§7${"%.1f".format(elem.scale)}x"
                context.drawText(tr, scaleStr, elem.x, elem.y + sh + 3, -1, true)
            }
        }

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mx = mouseX.toInt(); val my = mouseY.toInt()
        val elem = elements.lastOrNull { e ->
            mx >= e.x && mx <= e.x + e.screenW && my >= e.y && my <= e.y + e.screenH
        } ?: activeElement ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        if (!elem.canScale) return true

        val delta = if (verticalAmount > 0) 0.1f else -0.1f
        elem.scale = "%.1f".format((elem.scale + delta).coerceIn(0.5f, 5f)).toFloat()
        return true
    }

    fun onMousePress(mouseX: Double, mouseY: Double) {
        val mx = mouseX.toInt(); val my = mouseY.toInt()
        for (elem in elements.reversed()) {
            if (mx >= elem.x && mx <= elem.x + elem.screenW &&
                my >= elem.y && my <= elem.y + elem.screenH) {
                elem.dragging = true
                elem.dragOffX = mouseX - elem.x
                elem.dragOffY = mouseY - elem.y
                activeElement = elem
                return
            }
        }
        activeElement = null
    }

    fun onMouseRelease() {
        elements.forEach { it.dragging = false }
    }

    override fun close() {
        elements.forEach { it.onSave(it) }
        FamilyConfigManager.save()
        super.close()
    }

    override fun shouldPause() = false
}