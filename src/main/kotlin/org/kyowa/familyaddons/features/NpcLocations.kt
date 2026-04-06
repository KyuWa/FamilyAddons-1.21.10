package org.kyowa.familyaddons.features

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Box
import org.lwjgl.opengl.GL11

object NpcLocations {

    data class NpcInfo(val name: String, val location: String, val x: Double, val y: Double, val z: Double)
    data class ActiveNpcWaypoint(val name: String, val x: Double, val y: Double, val z: Double)

    val activeWaypoints = mutableListOf<ActiveNpcWaypoint>()

    private var _npcs: List<NpcInfo> = emptyList()
    val npcs: List<NpcInfo> get() = _npcs

    private fun loadNpcs() {
        try {
            val stream = NpcLocations::class.java.classLoader.getResourceAsStream("npcs.json")
                ?: return
            val json = stream.bufferedReader().readText()
            val type = object : TypeToken<List<NpcInfo>>() {}.type
            _npcs = Gson().fromJson(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasActiveWaypoints(): Boolean = activeWaypoints.isNotEmpty()

    fun findNpc(query: String): List<NpcInfo> {
        val lower = query.lowercase()
        return npcs.filter { it.name.lowercase().contains(lower) }
    }

    fun register() {
        // Load NPC data off main thread
        java.util.concurrent.CompletableFuture.runAsync { loadNpcs() }

        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ -> activeWaypoints.clear() }


    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        if (activeWaypoints.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        val immediate = client.bufferBuilders?.entityVertexConsumers ?: run { matrices.pop(); return }
        for (wp in activeWaypoints) {
            val box = Box(wp.x - 0.5, wp.y, wp.z - 0.5, wp.x + 0.5, wp.y + 2.0, wp.z + 0.5)
            val consumer = immediate.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), consumer, box, 1.0f, 0.84f, 0.0f, 1.0f)
        }
        immediate.draw(RenderLayer.getLines())

        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers ?: run { GL11.glEnable(GL11.GL_DEPTH_TEST); matrices.pop(); return }
        for (wp in activeWaypoints) {
            val box = Box(wp.x - 0.5, wp.y, wp.z - 0.5, wp.x + 0.5, wp.y + 2.0, wp.z + 0.5)
            val consumer = immediate2.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), consumer, box, 1.0f, 0.84f, 0.0f, 0.3f)
        }
        immediate2.draw(RenderLayer.getLines())
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()
    }
}
