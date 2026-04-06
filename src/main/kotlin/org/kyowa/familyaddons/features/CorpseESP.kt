package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11

object CorpseESP {

    data class Corpse(val x: Double, val y: Double, val z: Double, val label: String, val r: Float, val g: Float, val b: Float)

    val cachedCorpses = mutableListOf<Corpse>()
    private val claimed = mutableListOf<Triple<Double, Double, Double>>()

    private val CORPSE_ENTRY_REGEX = Regex("""^(\w+):\s*(LOOTED|NOT LOOTED)$""")

    data class HelmetInfo(val label: String, val r: Float, val g: Float, val b: Float)

    private val HELMET_MAP = mapOf(
        "Lapis Armor Helmet" to HelmetInfo("Lapis",    0.0f,  0.47f, 1.0f),
        "Mineral Helmet"     to HelmetInfo("Tungsten", 1.0f,  1.0f,  1.0f),
        "Yog Helmet"         to HelmetInfo("Umber",    0.71f, 0.38f, 0.13f),
        "Vanguard Helmet"    to HelmetInfo("Vanguard", 0.95f, 0.14f, 0.72f)
    )

    // Announce state
    private var lastArea: String? = null
    private var hasAnnounced = false
    private var announceCheckTick = 0
    private var inMineshaft = false

    private fun getFrozenCorpses(): List<Pair<String, Boolean>>? {
        val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return null
        val corpses = mutableListOf<Pair<String, Boolean>>()
        var inCorpseSection = false

        for (entry in tabList) {
            val raw = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
            if (raw.isEmpty()) continue

            if (raw == "Frozen Corpses:") {
                inCorpseSection = true
                continue
            }

            if (inCorpseSection) {
                if (raw.endsWith(":") && !raw.contains("LOOTED") && !raw.contains("NOT LOOTED")) break
                val match = CORPSE_ENTRY_REGEX.find(raw) ?: continue
                corpses.add(match.groupValues[1] to (match.groupValues[2] == "LOOTED"))
            }
        }

        return if (corpses.isNotEmpty()) corpses else null
    }

    fun hasCachedCorpses(): Boolean = cachedCorpses.isNotEmpty()

    // Returns ARGB color for outline mode, 0 if not a corpse or wrong mode
    fun getOutlineColor(entity: net.minecraft.entity.Entity): Int {
        val config = FamilyConfigManager.config.mining
        if (!config.corpseESP) return 0
        if (config.corpseDrawingStyle != 1) return 0
        val ex = entity.x; val ey = entity.y; val ez = entity.z
        val corpse = cachedCorpses.firstOrNull { c ->
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
        // Area detection + corpse announce tick handler
        var areaCheckTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register

            // Throttle area detection to every 10 ticks
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
                    hasAnnounced = false
                    announceCheckTick = 0
                    cachedCorpses.clear()
                } else {
                    inMineshaft = false
                    hasAnnounced = false
                    announceCheckTick = 0
                }
            }

            // Announce — only if feature enabled
            if (!FamilyConfigManager.config.mining.corpseAnnounce) return@register
            if (!inMineshaft || hasAnnounced) return@register

            announceCheckTick++
            // Wait 60 ticks (3s) before first attempt, then retry every 20 ticks
            if (announceCheckTick < 100) return@register
            if (announceCheckTick != 100 && (announceCheckTick - 100) % 20 != 0) return@register

            val corpses = getFrozenCorpses()
            if (corpses == null) return@register  // tab list not ready yet, retry next interval

            val lapisCount = corpses.count { it.first == "Lapis" }
            val hasVanguard = corpses.any { it.first == "Vanguard" }
            if (lapisCount == 0 && !hasVanguard) return@register  // no relevant corpses, retry

            hasAnnounced = true
            val msg = when {
                hasVanguard && lapisCount == 0 -> "/pc Vanguard Mineshaft"
                hasVanguard -> "/pc Vanguard Mineshaft | Corpses: ${lapisCount}x Lapis"
                else -> "/pc Corpses: ${lapisCount}x Lapis"
            }
            player.networkHandler.sendChatMessage(msg)
        }

        // Scan entities every 10 ticks — only in Mineshaft
        var scanTick = 0
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.mining.corpseESP) return@register
            if (!inMineshaft) return@register
            val world = client.world ?: return@register
            if (scanTick++ % 10 != 0) return@register

            for (entity in world.entities) {
                if (entity !is ArmorStandEntity) continue
                if (entity.isInvisible) continue

                val ex = entity.x
                val ey = entity.y
                val ez = entity.z

                // Skip if claimed
                if (claimed.any { (cx, cy, cz) ->
                        Math.abs(cx - ex) < 2 && Math.abs(cy - ey) < 2 && Math.abs(cz - ez) < 2
                    }) continue

                val helmet = entity.getEquippedStack(EquipmentSlot.HEAD)
                if (helmet.isEmpty) continue

                val helmetName = helmet.name.string.replace(COLOR_CODE_REGEX, "").trim()
                val info = HELMET_MAP[helmetName] ?: continue
                val label = info.label; val r = info.r; val g = info.g; val b = info.b

                // Add to cache if not already there
                if (cachedCorpses.none { c ->
                        Math.abs(c.x - ex) < 2 && Math.abs(c.y - ey) < 2 && Math.abs(c.z - ez) < 2
                    }) {
                    cachedCorpses.add(Corpse(ex, ey, ez, label, r, g, b))
                }
            }
        }

        // Detect corpse loot message
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            val match = Regex("""^\s*(.+?)\s+CORPSE LOOT!\s*$""").find(plain)
            if (match != null) {
                val corpseName = match.groupValues[1].trim()
                val client = MinecraftClient.getInstance()
                val player = client.player ?: return@register true

                val px = player.x
                val py = player.y
                val pz = player.z

                // Remove from cache
                cachedCorpses.removeIf { c ->
                    Math.abs(c.x - px) < 5 &&
                            Math.abs(c.y - py) < 5 &&
                            Math.abs(c.z - pz) < 5 &&
                            c.label.equals(corpseName, ignoreCase = true)
                }

                claimed.add(Triple(px, py, pz))

                if (FamilyConfigManager.config.mining.corpseAnnounce) {
                    val fx = px.toInt(); val fy = py.toInt(); val fz = pz.toInt()
                    client.execute {
                        player.networkHandler.sendChatMessage("/pc x: $fx, y: $fy, z: $fz | ($corpseName Corpse)")
                    }
                }
            }
            true
        }

        // Clear on world change
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            cachedCorpses.clear()
            claimed.clear()
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            cachedCorpses.clear()
            claimed.clear()
            lastArea = null
            hasAnnounced = false
            announceCheckTick = 0
            inMineshaft = false
        }
    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        if (!FamilyConfigManager.config.mining.corpseESP) return
        if (cachedCorpses.isEmpty()) return
        // Skip box rendering if outline mode
        if (FamilyConfigManager.config.mining.corpseDrawingStyle == 1) return

        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Pass 1: normal depth
        val immediate = client.bufferBuilders?.entityVertexConsumers ?: run { matrices.pop(); return }
        for (c in cachedCorpses) {
            val box = Box(c.x - 0.5, c.y, c.z - 0.5, c.x + 0.5, c.y + 2.0, c.z + 0.5)
            VertexRendering.drawBox(matrices.peek(), immediate.getBuffer(RenderLayer.getLines()), box, c.r, c.g, c.b, 1.0f)
        }
        immediate.draw(RenderLayer.getLines())

        // Pass 2: through walls
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers ?: run { GL11.glEnable(GL11.GL_DEPTH_TEST); matrices.pop(); return }
        for (c in cachedCorpses) {
            val box = Box(c.x - 0.5, c.y, c.z - 0.5, c.x + 0.5, c.y + 2.0, c.z + 0.5)
            VertexRendering.drawBox(matrices.peek(), immediate2.getBuffer(RenderLayer.getLines()), box, c.r, c.g, c.b, 1.0f)
        }
        immediate2.draw(RenderLayer.getLines())
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()

        // Labels
        val immediate3 = client.bufferBuilders?.entityVertexConsumers ?: return
        for (c in cachedCorpses) {
            val dx = c.x - cam.x; val dy = c.y - cam.y; val dz = c.z - cam.z
            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz).toInt()
            renderLabel(matrices, camera, immediate3, c.x, c.y + 2.2, c.z, "§f${c.label} §7(${dist}m)", c.r, c.g, c.b)
        }
    }

    private fun renderLabel(
        matrices: MatrixStack, camera: Camera,
        consumers: net.minecraft.client.render.VertexConsumerProvider,
        x: Double, y: Double, z: Double, text: String,
        r: Float, g: Float, b: Float
    ) {
        val client = MinecraftClient.getInstance()
        val cam = camera.pos
        matrices.push()
        matrices.translate(x - cam.x, y - cam.y, z - cam.z)
        matrices.multiply(camera.rotation)
        val scale = 0.025f
        matrices.scale(-scale, -scale, scale)
        val tr = client.textRenderer
        val w = tr.getWidth(text.replace(COLOR_CODE_REGEX, ""))
        tr.draw(
            text, -w / 2f, 0f, -1, false,
            matrices.peek().positionMatrix, consumers,
            net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH,
            0, 0xF000F0
        )
        matrices.pop()
    }
}
