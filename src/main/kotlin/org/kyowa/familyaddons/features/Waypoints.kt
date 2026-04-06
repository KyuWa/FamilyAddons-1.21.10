package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text
import net.minecraft.util.math.Box
import org.kyowa.familyaddons.FamilyAddons
import org.lwjgl.opengl.GL11
import java.io.File

object Waypoints {

    data class Waypoint(val x: Int, val y: Int, val z: Int, var label: String)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile = File("config/familyaddons_waypoints.json")
    private val waypointsByIsland = mutableMapOf<String, MutableList<Waypoint>>()



    private var keyWasDown = false
    // Color stored as RGB ints, defaults to red
    val waypointR get() = parseColor(0)
    val waypointG get() = parseColor(1)
    val waypointB get() = parseColor(2)

    private fun parseColor(idx: Int): Float {
        // color string format: "opacity:alpha:r:g:b" or fallback to colorR/G/B
        return try {
            val parts = FamilyConfigManager.config.waypoints.color.split(":")
            parts[idx + 2].toInt() / 255f
        } catch (e: Exception) {
            when (idx) {
                0 -> FamilyConfigManager.config.waypoints.colorR / 255f
                1 -> FamilyConfigManager.config.waypoints.colorG / 255f
                else -> FamilyConfigManager.config.waypoints.colorB / 255f
            }
        }
    }

    fun register() {
        load()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!FamilyConfigManager.config.waypoints.enabled) return@register
            val player = client.player ?: return@register

            // Don't fire when typing in chat or any screen is open
            if (client.currentScreen != null) { keyWasDown = false; return@register }

            val keyDown = org.lwjgl.glfw.GLFW.glfwGetKey(
                client.window.handle,
                FamilyConfigManager.config.waypoints.placeKey
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS

            if (keyDown && !keyWasDown) {
                val island = getCurrentIsland()
                if (island == null) {
                    chat("§cCan't detect island — open tab list first.")
                } else {
                    val x = player.blockX
                    val y = player.blockY - 1
                    val z = player.blockZ
                    val list = waypointsByIsland.getOrPut(island) { mutableListOf() }
                    val label = (list.size + 1).toString()
                    list.add(Waypoint(x, y, z, label))
                    save()
                    chat("§e$label§a placed on §e$island§a at §b$x, $y, $z")
                }
            }
            keyWasDown = keyDown
        }
    }

    fun hasWaypoints(): Boolean = waypointsByIsland.values.any { it.isNotEmpty() }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        if (!FamilyConfigManager.config.waypoints.enabled) return
        val island = getCurrentIsland() ?: return
        val wps = waypointsByIsland[island] ?: return
        if (wps.isEmpty()) return

        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        // Build a fresh immediate buffer
        val tessellator = net.minecraft.client.render.Tessellator.getInstance()

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Pass 1: normal depth test — bright outline
        val immediate = client.bufferBuilders?.entityVertexConsumers ?: run { matrices.pop(); return }
        for (wp in wps) {
            val box = Box(wp.x.toDouble(), wp.y.toDouble(), wp.z.toDouble(), wp.x + 1.0, wp.y + 1.0, wp.z + 1.0)
            val consumer = immediate.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), consumer, box, waypointR, waypointG, waypointB, 1.0f)
        }
        immediate.draw(RenderLayer.getLines())

        // Pass 2: disable depth — faint outline through walls
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers ?: run { GL11.glEnable(GL11.GL_DEPTH_TEST); matrices.pop(); return }
        for (wp in wps) {
            val box = Box(wp.x.toDouble(), wp.y.toDouble(), wp.z.toDouble(), wp.x + 1.0, wp.y + 1.0, wp.z + 1.0)
            val consumer = immediate2.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), consumer, box, waypointR, waypointG, waypointB, 0.3f)
        }
        immediate2.draw(RenderLayer.getLines())
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()

        // Labels
        if (FamilyConfigManager.config.waypoints.showLabels) {
            val immediate3 = client.bufferBuilders?.entityVertexConsumers ?: return
            for (wp in wps) {
                val dx = wp.x - cam.x
                val dy = wp.y - cam.y
                val dz = wp.z - cam.z
                val dist = Math.sqrt(dx * dx + dy * dy + dz * dz).toInt()
                renderLabel(matrices, camera, immediate3, wp.x + 0.5, wp.y + 1.5, wp.z + 0.5, "${wp.label} (${dist}m)")
            }
        }
    }

    private fun renderLabel(
        matrices: MatrixStack, camera: Camera,
        consumers: VertexConsumerProvider,
        x: Double, y: Double, z: Double, text: String
    ) {
        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        matrices.push()
        matrices.translate(x - cam.x, y - cam.y, z - cam.z)
        matrices.multiply(camera.rotation)
        val scale = 0.025f
        matrices.scale(-scale, -scale, scale)

        val tr = client.textRenderer
        val w = tr.getWidth(text)
        tr.draw(
            text, -w / 2f, 0f, -1, false,
            matrices.peek().positionMatrix, consumers,
            net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH,
            0, 0xF000F0
        )
        matrices.pop()
    }

    fun getCurrentIsland(): String? {
        return try {
            val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return null
            for (entry in tabList) {
                val name = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                if (name.startsWith("Area:")) return name.removePrefix("Area:").trim()
            }
            null
        } catch (e: Exception) { null }
    }

    fun getWaypoints(island: String): List<Waypoint> = waypointsByIsland[island] ?: emptyList()

    fun removeWaypoint(island: String, index: Int): Boolean {
        val list = waypointsByIsland[island] ?: return false
        if (index < 0 || index >= list.size) return false
        list.removeAt(index)
        save()
        return true
    }

    fun clearWaypoints(island: String) {
        waypointsByIsland[island]?.clear()
        save()
    }

    fun renameWaypoint(island: String, index: Int, newName: String): Boolean {
        val list = waypointsByIsland[island] ?: return false
        if (index < 0 || index >= list.size) return false
        list[index].label = newName
        save()
        return true
    }

    private fun load() {
        try {
            if (!saveFile.exists()) return
            val type = object : TypeToken<Map<String, List<Map<String, Any>>>>() {}.type
            val raw = gson.fromJson<Map<String, List<Map<String, Any>>>>(saveFile.readText(), type)
            raw.forEach { (island, wps) ->
                waypointsByIsland[island] = wps.map {
                    Waypoint(
                        (it["x"] as? Double)?.toInt() ?: 0,
                        (it["y"] as? Double)?.toInt() ?: 0,
                        (it["z"] as? Double)?.toInt() ?: 0,
                        it["label"] as? String ?: "?"
                    )
                }.toMutableList()
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Waypoints load error: ${e.message}")
        }
    }

    private fun save() {
        try {
            saveFile.parentFile.mkdirs()
            saveFile.writeText(gson.toJson(waypointsByIsland))
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Waypoints save error: ${e.message}")
        }
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal("§6[FA] $msg"), false)
        }
    }
}
