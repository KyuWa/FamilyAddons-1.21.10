package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.util.math.Box
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11
import kotlin.math.sqrt

object CorpseESP {

    data class Corpse(
        val x: Double, val y: Double, val z: Double,
        val label: String,
        val r: Float, val g: Float, val b: Float,
        var looted: Boolean = false
    )

    val cachedCorpses = mutableListOf<Corpse>()

    private val LOOT_MESSAGE_REGEX = Regex("""^\s*(.+?)\s+CORPSE LOOT!\s*$""")

    data class HelmetInfo(val label: String, val r: Float, val g: Float, val b: Float)

    private val HELMET_MAP = mapOf(
        "Lapis Armor Helmet" to HelmetInfo("Lapis",    0.0f,  0.47f, 1.0f),
        "Mineral Helmet"     to HelmetInfo("Tungsten", 1.0f,  1.0f,  1.0f),
        "Yog Helmet"         to HelmetInfo("Umber",    0.71f, 0.38f, 0.13f),
        "Vanguard Helmet"    to HelmetInfo("Vanguard", 0.95f, 0.14f, 0.72f)
    )

    private var lastArea: String? = null
    private var inMineshaft = false

    fun hasCachedCorpses(): Boolean = cachedCorpses.any { !it.looted }

    fun getOutlineColor(entity: net.minecraft.entity.Entity): Int {
        val config = FamilyConfigManager.config.mining
        if (!config.corpseESP) return 0
        if (config.corpseDrawingStyle != 1) return 0
        val ex = entity.x; val ey = entity.y; val ez = entity.z
        val corpse = cachedCorpses.firstOrNull { c ->
            !c.looted &&
                    Math.abs(c.x - ex) < 1.5 && Math.abs(c.y - ey) < 1.5 && Math.abs(c.z - ez) < 1.5
        } ?: return 0
        val r = (corpse.r * 255).toInt()
        val g = (corpse.g * 255).toInt()
        val b = (corpse.b * 255).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun debugTabList(): String {
        val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return "no tab list"
        return tabList.mapNotNull { it.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
    }

    fun register() {
        var areaCheckTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            var currentArea = lastArea
            if (areaCheckTick++ % 10 == 0) {
                val tabList = client.networkHandler?.playerList ?: return@register
                currentArea = null
                for (entry in tabList) {
                    val name = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                    if (name.startsWith("Area:")) { currentArea = name.removePrefix("Area:").trim(); break }
                }
            }

            if (currentArea != lastArea) {
                lastArea = currentArea
                if (currentArea == "Mineshaft") {
                    inMineshaft = true
                    cachedCorpses.clear()
                } else {
                    inMineshaft = false
                }
            }
        }

        var scanTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.mining.corpseESP) return@register
            if (!inMineshaft) return@register
            val world = client.world ?: return@register
            if (scanTick++ % 10 != 0) return@register

            for (entity in world.entities) {
                if (entity !is ArmorStandEntity) continue
                if (entity.isInvisible) continue

                val ex = entity.x; val ey = entity.y; val ez = entity.z

                if (cachedCorpses.any { c ->
                        Math.abs(c.x - ex) < 2 && Math.abs(c.y - ey) < 2 && Math.abs(c.z - ez) < 2
                    }) continue

                val helmet = entity.getEquippedStack(EquipmentSlot.HEAD)
                if (helmet.isEmpty) continue

                val helmetName = helmet.name.string.replace(COLOR_CODE_REGEX, "").trim()
                val info = HELMET_MAP[helmetName] ?: continue

                cachedCorpses.add(Corpse(ex, ey, ez, info.label, info.r, info.g, info.b))
            }
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            val lootMatch = LOOT_MESSAGE_REGEX.find(plain)
            if (lootMatch != null) {
                val corpseName = lootMatch.groupValues[1].trim()
                val client = MinecraftClient.getInstance()
                val player = client.player ?: return@register true

                val px = player.x; val py = player.y; val pz = player.z

                cachedCorpses
                    .filter { !it.looted && it.label.equals(corpseName, ignoreCase = true) }
                    .minByOrNull { c ->
                        val dx = c.x - px; val dy = c.y - py; val dz = c.z - pz
                        dx * dx + dy * dy + dz * dz
                    }
                    ?.let { it.looted = true }

                if (FamilyConfigManager.config.mining.corpseAnnounce) {
                    val fx = px.toInt(); val fy = py.toInt(); val fz = pz.toInt()
                    client.execute {
                        player.networkHandler.sendChatMessage("/pc x: $fx, y: $fy, z: $fz | ($corpseName Corpse)")
                    }
                }
            }
            true
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> cachedCorpses.clear() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            cachedCorpses.clear()
            lastArea = null
            inMineshaft = false
        }
    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        if (!FamilyConfigManager.config.mining.corpseESP) return
        val visible = cachedCorpses.filter { !it.looted }
        if (visible.isEmpty()) return
        if (FamilyConfigManager.config.mining.corpseDrawingStyle == 1) return

        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Pass 1: with depth
        val immediate = client.bufferBuilders?.entityVertexConsumers ?: run { matrices.pop(); return }
        for (c in visible) {
            val box = Box(c.x - 0.5, c.y, c.z - 0.5, c.x + 0.5, c.y + 2.0, c.z + 0.5)
            VertexRendering.drawBox(matrices.peek(), immediate.getBuffer(RenderLayer.getLines()), box, c.r, c.g, c.b, 1.0f)
        }
        immediate.draw(RenderLayer.getLines())

        // Pass 2: through walls, full alpha
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers ?: run { GL11.glEnable(GL11.GL_DEPTH_TEST); matrices.pop(); return }
        for (c in visible) {
            val box = Box(c.x - 0.5, c.y, c.z - 0.5, c.x + 0.5, c.y + 2.0, c.z + 0.5)
            VertexRendering.drawBox(matrices.peek(), immediate2.getBuffer(RenderLayer.getLines()), box, c.r, c.g, c.b, 1.0f)
        }
        immediate2.draw(RenderLayer.getLines())
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()

        // Labels
        val labelBuffer = client.bufferBuilders?.entityVertexConsumers ?: return
        for (c in visible) {
            val dx = c.x - cam.x; val dy = c.y + 2.2 - cam.y; val dz = c.z - cam.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            val distInt = dist.toInt()
            renderLabel(matrices, labelBuffer, cam.x, cam.y, cam.z,
                c.x, c.y + 2.2, c.z, "§f${c.label} §7(${distInt}m)", dist)
        }
        labelBuffer.draw()
    }

    private fun renderLabel(
        matrices: MatrixStack,
        consumers: VertexConsumerProvider,
        camX: Double, camY: Double, camZ: Double,
        x: Double, y: Double, z: Double,
        text: String,
        dist: Double
    ) {
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer

        // Scale grows with distance but capped: min=1.0, max=5.0
        val scale = (dist / 10.0).coerceIn(1.0, 5.0).toFloat() * 0.025f

        matrices.push()
        matrices.translate(x - camX, y - camY, z - camZ)
        matrices.multiply(client.gameRenderer.camera.rotation)
        matrices.scale(scale, -scale, scale)

        val w = tr.getWidth(text.replace(COLOR_CODE_REGEX, ""))
        tr.draw(
            text, -w / 2f, 0f, -1, true,
            matrices.peek().positionMatrix, consumers,
            net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH,
            0, LightmapTextureManager.MAX_LIGHT_COORDINATE
        )
        matrices.pop()
    }
}