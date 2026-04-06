package org.kyowa.familyaddons.features

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.particle.ParticleTypes
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import java.io.File

object Parkour {

    data class Checkpoint(
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float = 0f
    )

    data class ParkourRoute(
        val checkpoints: MutableList<Checkpoint> = mutableListOf(),
        var bestTimeMs: Long = Long.MAX_VALUE
    )

    data class SaveData(
        val parkours: MutableMap<String, ParkourRoute> = mutableMapOf(),
        var hudX: Int = 10, var hudY: Int = 60, var hudScale: Float = 1.0f,
        var arrowHudX: Int = 100, var arrowHudY: Int = 100, var arrowHudScale: Float = 1.0f,
        var cpHudX: Int = 200, var cpHudY: Int = 150, var cpHudScale: Float = 1.5f
    )

    var hudX = 10; var hudY = 60; var hudScale = 1.0f
    var arrowHudX = 100; var arrowHudY = 100; var arrowHudScale = 1.0f
    var cpHudX = 200; var cpHudY = 150; var cpHudScale = 1.5f

    private var cpNotifText = ""
    private var cpNotifTimer = 0
    private var startKeyWasDown = false

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile get() = File(
        MinecraftClient.getInstance().runDirectory,
        "config/familyaddons/parkours.json"
    )

    var saveData = SaveData()

    // Active route during editing/running
    private var activeRouteName = "default"
    private val activeRoute get() = saveData.parkours.getOrPut(activeRouteName) { ParkourRoute() }
    private val activeRouteOrNull get() = saveData.parkours[activeRouteName]

    private var isActive = false
    private var nextCheckpointIdx = 0
    private var timerStartMs = 0L
    private var currentTimeMs = 0L
    private var lastPos: Vec3d? = null
    private var finishDisplayTimer = 0
    var isEditing = false

    fun load() {
        try {
            if (!saveFile.exists()) {
                val bundled = Parkour::class.java.getResourceAsStream("/default_parkours.json")
                if (bundled != null) {
                    saveFile.parentFile.mkdirs()
                    saveFile.writeText(bundled.bufferedReader().readText())
                }
            }
            if (saveFile.exists()) {
                val type = object : TypeToken<SaveData>() {}.type
                saveData = gson.fromJson(saveFile.readText(), type) ?: SaveData()
            }
        } catch (e: Exception) { e.printStackTrace(); saveData = SaveData() }
        activeRouteName = FamilyConfigManager.config.parkour.activeParkour
        hudX = saveData.hudX; hudY = saveData.hudY; hudScale = saveData.hudScale
        arrowHudX = saveData.arrowHudX; arrowHudY = saveData.arrowHudY; arrowHudScale = saveData.arrowHudScale
        cpHudX = saveData.cpHudX; cpHudY = saveData.cpHudY; cpHudScale = saveData.cpHudScale
    }

    fun save() {
        try {
            saveData.hudX = hudX; saveData.hudY = hudY; saveData.hudScale = hudScale
            saveData.arrowHudX = arrowHudX; saveData.arrowHudY = arrowHudY; saveData.arrowHudScale = arrowHudScale
            saveData.cpHudX = cpHudX; saveData.cpHudY = cpHudY; saveData.cpHudScale = cpHudScale
            saveFile.parentFile.mkdirs()
            saveFile.writeText(gson.toJson(saveData))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun register() {
        load()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register
            val world = client.world ?: return@register
            if (finishDisplayTimer > 0) finishDisplayTimer--
            if (cpNotifTimer > 0) cpNotifTimer--

            val config = FamilyConfigManager.config.parkour
            if (!config.enabled) return@register

            // Start keybind — ignore if any screen is open (chat, inventory etc)
            val startKey = config.startKey
            if (startKey != GLFW.GLFW_KEY_UNKNOWN && !isActive && client.currentScreen == null) {
                val window = client.window.handle
                val pressed = GLFW.glfwGetKey(window, startKey) == GLFW.GLFW_PRESS
                if (pressed && !startKeyWasDown) start(config.activeParkour)
                startKeyWasDown = pressed
            } else if (startKey == GLFW.GLFW_KEY_UNKNOWN || client.currentScreen != null) startKeyWasDown = false

            if (!isActive) { lastPos = Vec3d(player.x, player.y, player.z); return@register }
            val route = activeRoute
            if (route.checkpoints.isEmpty()) return@register

            val cur = Vec3d(player.x, player.y, player.z)
            val prev = lastPos ?: cur
            lastPos = cur

            val cp = route.checkpoints[nextCheckpointIdx]
            if (crossedPlane(prev, cur, cp)) {
                repeat(20) { i ->
                    val angle = i * (Math.PI * 2.0 / 20)
                    val yawRad = Math.toRadians(cp.yaw.toDouble())
                    world.addParticle(ParticleTypes.HAPPY_VILLAGER, true, false,
                        cp.x + Math.cos(angle) * Math.cos(yawRad) * RING_RADIUS,
                        cp.y + Math.sin(angle) * RING_RADIUS,
                        cp.z + Math.cos(angle) * Math.sin(yawRad) * RING_RADIUS,
                        0.0, 0.1, 0.0)
                }
                when {
                    nextCheckpointIdx == 0 -> {
                        timerStartMs = System.currentTimeMillis(); currentTimeMs = 0L; nextCheckpointIdx = 1
                        cpNotifText = "§a§lGO!"; cpNotifTimer = 60; chat("§aGo!")
                    }
                    nextCheckpointIdx == route.checkpoints.size - 1 -> {
                        val elapsed = System.currentTimeMillis() - timerStartMs
                        currentTimeMs = elapsed
                        if (elapsed < route.bestTimeMs) {
                            route.bestTimeMs = elapsed; save()
                            chat("§6§lNew best! §f${formatTime(elapsed)}")
                        } else chat("§eFinished! §f${formatTime(elapsed)} §7| Best: §a${formatTime(route.bestTimeMs)}")
                        cpNotifText = "§6§lFINISH! §f${formatTime(elapsed)}"; cpNotifTimer = 120
                        finishDisplayTimer = 100; isActive = false; nextCheckpointIdx = 0
                    }
                    else -> {
                        nextCheckpointIdx++
                        cpNotifText = "§e§lCheckpoint $nextCheckpointIdx/${route.checkpoints.size - 1}"; cpNotifTimer = 60
                    }
                }
            }
            if (isActive && timerStartMs > 0) currentTimeMs = System.currentTimeMillis() - timerStartMs
        }

        // Timer HUD
        HudRenderCallback.EVENT.register { ctx, _ ->
            if (!isActive && finishDisplayTimer <= 0) return@register
            val tr = MinecraftClient.getInstance().textRenderer
            val m = ctx.matrices
            m.pushMatrix(); m.translate(hudX.toFloat(), hudY.toFloat()); m.scale(hudScale, hudScale)
            val timeStr = formatTime(currentTimeMs)
            val best = activeRoute.bestTimeMs
            val bestStr = if (best == Long.MAX_VALUE) "§7--:--.---" else "§a${formatTime(best)}"
            ctx.drawText(tr, if (isActive) "§f$timeStr" else "§f$timeStr  §7Best: $bestStr", 0, 0, -1, true)
            m.popMatrix()
        }

        // Checkpoint notification HUD
        HudRenderCallback.EVENT.register { ctx, _ ->
            if (cpNotifTimer <= 0) return@register
            val alpha = (cpNotifTimer.toFloat() / 40f).coerceIn(0f, 1f)
            val tr = MinecraftClient.getInstance().textRenderer
            val m = ctx.matrices
            m.pushMatrix(); m.translate(cpHudX.toFloat(), cpHudY.toFloat()); m.scale(cpHudScale, cpHudScale)
            ctx.drawText(tr, cpNotifText, 0, 0, ((alpha * 255).toInt() shl 24) or 0xFFFFFF, true)
            m.popMatrix()
        }

        // Arrow HUD
        HudRenderCallback.EVENT.register { ctx, _ ->
            if (!isActive || nextCheckpointIdx >= activeRoute.checkpoints.size) return@register
            val player = MinecraftClient.getInstance().player ?: return@register
            val tr = MinecraftClient.getInstance().textRenderer
            val cp = activeRoute.checkpoints[nextCheckpointIdx]
            val dx = cp.x - player.x; val dz = cp.z - player.z
            val dist = Math.sqrt(dx * dx + dz * dz)
            val bearing = Math.toDegrees(Math.atan2(dx, -dz))
            var rel = bearing - (player.yaw + 180.0)
            while (rel > 180) rel -= 360; while (rel < -180) rel += 360
            val arrow = when {
                rel < -157.5 || rel > 157.5 -> "↓"
                rel < -112.5 -> "↙"; rel < -67.5 -> "←"; rel < -22.5 -> "↖"
                rel < 22.5 -> "↑"; rel < 67.5 -> "↗"; rel < 112.5 -> "→"; else -> "↘"
            }
            val distStr = if (dist > 1000) "${"%.1f".format(dist/1000)}km" else "${"%.0f".format(dist)}m"
            val m = ctx.matrices
            m.pushMatrix(); m.translate(arrowHudX.toFloat(), arrowHudY.toFloat()); m.scale(arrowHudScale, arrowHudScale)
            ctx.drawText(tr, "§e$arrow", 0, 0, -1, true)
            ctx.drawText(tr, "§f$distStr", 0, 12, -1, true)
            m.popMatrix()
        }
    }

    // --- Geometry ---
    private const val RING_RADIUS = 1.5f
    private const val RING_SEGMENTS = 32
    private const val TUBE_RADIUS = 0.18f
    private const val TUBE_SEGMENTS = 12

    private fun ringNormal(yaw: Float, pitch: Float): Vec3d {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return Vec3d(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)).normalize()
    }
    private fun ringRight(yaw: Float): Vec3d {
        val y = Math.toRadians(yaw.toDouble())
        return Vec3d(Math.cos(y), 0.0, Math.sin(y)).normalize()
    }
    private fun ringUp(yaw: Float, pitch: Float): Vec3d {
        val n = ringNormal(yaw, pitch); val r = ringRight(yaw)
        return Vec3d(r.y*n.z - r.z*n.y, r.z*n.x - r.x*n.z, r.x*n.y - r.y*n.x).normalize()
    }

    private fun crossedPlane(prev: Vec3d, cur: Vec3d, cp: Checkpoint): Boolean {
        val center = Vec3d(cp.x, cp.y, cp.z)
        val normal = ringNormal(cp.yaw, cp.pitch)
        val dPrev = prev.subtract(center).dotProduct(normal)
        val dCur = cur.subtract(center).dotProduct(normal)
        if (dPrev * dCur > 0) return false
        val mid = prev.add(cur).multiply(0.5)
        val diff = mid.subtract(center)
        val lateral = diff.subtract(normal.multiply(diff.dotProduct(normal)))
        return lateral.length() < RING_RADIUS + 1.0
    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        val route = activeRoute
        if (route.checkpoints.isEmpty()) return
        if (!isActive && !isEditing) return

        val immediate = MinecraftClient.getInstance().bufferBuilders?.entityVertexConsumers ?: return
        val camX = camera.pos.x; val camY = camera.pos.y; val camZ = camera.pos.z

        fun drawAll(alpha: Float) {
            val buf = immediate.getBuffer(RenderLayer.getLines())
            route.checkpoints.forEachIndexed { i, cp ->
                if (!isEditing && i < nextCheckpointIdx) return@forEachIndexed
                val isNext = i == nextCheckpointIdx
                val r = if (isNext) 0.2f else 0.8f
                val g = if (isNext) 1.0f else 0.8f
                val b = if (isNext) 0.2f else 0.8f
                drawTorus(buf, matrices, cp, camX, camY, camZ, r, g, b, alpha)
            }
            (immediate as? VertexConsumerProvider.Immediate)?.draw(RenderLayer.getLines())
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST); drawAll(0.9f)
        GL11.glDisable(GL11.GL_DEPTH_TEST); drawAll(0.15f)
        GL11.glEnable(GL11.GL_DEPTH_TEST)
    }

    private fun drawTorus(
        buf: net.minecraft.client.render.VertexConsumer, matrices: MatrixStack,
        cp: Checkpoint, camX: Double, camY: Double, camZ: Double,
        r: Float, g: Float, b: Float, alpha: Float
    ) {
        val mat = matrices.peek()
        val cx = (cp.x - camX).toFloat(); val cy = (cp.y - camY).toFloat(); val cz = (cp.z - camZ).toFloat()
        val right = ringRight(cp.yaw); val up = ringUp(cp.yaw, cp.pitch); val normal = ringNormal(cp.yaw, cp.pitch)
        val nx = normal.x.toFloat(); val ny = normal.y.toFloat(); val nz = normal.z.toFloat()

        for (i in 0 until RING_SEGMENTS) {
            val a0 = (i.toDouble() / RING_SEGMENTS) * Math.PI * 2
            val a1 = ((i + 1).toDouble() / RING_SEGMENTS) * Math.PI * 2
            for (j in 0 until TUBE_SEGMENTS) {
                val p0 = (j.toDouble() / TUBE_SEGMENTS) * Math.PI * 2
                val p1 = ((j + 1).toDouble() / TUBE_SEGMENTS) * Math.PI * 2
                val v00 = tpt(cx, cy, cz, right, up, normal, a0, p0)
                val v10 = tpt(cx, cy, cz, right, up, normal, a1, p0)
                val v11 = tpt(cx, cy, cz, right, up, normal, a1, p1)
                val v01 = tpt(cx, cy, cz, right, up, normal, a0, p1)
                buf.vertex(mat.positionMatrix, v00[0], v00[1], v00[2]).color(r, g, b, alpha).normal(mat, nx, ny, nz)
                buf.vertex(mat.positionMatrix, v10[0], v10[1], v10[2]).color(r, g, b, alpha).normal(mat, nx, ny, nz)
                buf.vertex(mat.positionMatrix, v00[0], v00[1], v00[2]).color(r, g, b, alpha).normal(mat, nx, ny, nz)
                buf.vertex(mat.positionMatrix, v01[0], v01[1], v01[2]).color(r, g, b, alpha).normal(mat, nx, ny, nz)
            }
        }
    }

    private fun tpt(cx: Float, cy: Float, cz: Float, right: Vec3d, up: Vec3d, normal: Vec3d, a: Double, p: Double): FloatArray {
        val ox = (right.x * Math.cos(a) + up.x * Math.sin(a)).toFloat()
        val oy = (right.y * Math.cos(a) + up.y * Math.sin(a)).toFloat()
        val oz = (right.z * Math.cos(a) + up.z * Math.sin(a)).toFloat()
        val tcx = cx + ox * RING_RADIUS; val tcy = cy + oy * RING_RADIUS; val tcz = cz + oz * RING_RADIUS
        return floatArrayOf(
            tcx + (ox * Math.cos(p) + normal.x.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS,
            tcy + (oy * Math.cos(p) + normal.y.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS,
            tcz + (oz * Math.cos(p) + normal.z.toFloat() * Math.sin(p)).toFloat() * TUBE_RADIUS
        )
    }

    fun hasRings() = activeRoute.checkpoints.isNotEmpty() && (isActive || isEditing)

    // --- Commands ---
    fun addCheckpoint(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        activeRoute.checkpoints.add(Checkpoint(x, y, z, yaw, pitch)); save()
        chat("§aCheckpoint ${activeRoute.checkpoints.size} added to §e$activeRouteName")
    }
    fun removeLast() {
        if (activeRoute.checkpoints.isEmpty()) { chat("§cNo checkpoints."); return }
        activeRoute.checkpoints.removeLast(); save()
        chat("§eRemoved last. §7${activeRoute.checkpoints.size} remaining.")
    }
    fun clearAll() {
        activeRoute.checkpoints.clear(); activeRoute.bestTimeMs = Long.MAX_VALUE; save()
        chat("§cCleared §e$activeRouteName")
    }

    fun start(name: String = activeRouteName) {
        val route = saveData.parkours[name]
        if (route == null || route.checkpoints.size < 2) {
            chat("§cParkour §e$name §chas less than 2 checkpoints or doesn't exist."); return
        }
        activeRouteName = name
        isActive = true; isEditing = false; nextCheckpointIdx = 0
        currentTimeMs = 0L; timerStartMs = 0L
        chat("§aStarted §e$name§a! Run to the §egreen ring§a!")
    }
    fun stop() { isActive = false; nextCheckpointIdx = 0; currentTimeMs = 0L; chat("§cRun stopped.") }
    fun edit(name: String = FamilyConfigManager.config.parkour.activeParkour) {
        activeRouteName = name
        isEditing = !isEditing
        chat(if (isEditing) "§aEdit mode ON §7— editing §e$activeRouteName" else "§7Edit mode OFF")
    }
    fun listCheckpoints() {
        if (activeRoute.checkpoints.isEmpty()) { chat("§cNo checkpoints in §e$activeRouteName§c."); return }
        chat("§6$activeRouteName §7(${activeRoute.checkpoints.size} checkpoints):")
        activeRoute.checkpoints.forEachIndexed { i, cp ->
            val label = when(i) { 0 -> "§aSTART"; activeRoute.checkpoints.size-1 -> "§cEND"; else -> "§7CP $i" }
            chat("  $label §f${cp.x.toInt()}, ${cp.y.toInt()}, ${cp.z.toInt()}")
        }
        if (activeRoute.bestTimeMs != Long.MAX_VALUE) chat("§6Best: §f${formatTime(activeRoute.bestTimeMs)}")
    }
    fun listParkours() {
        val parkours = saveData.parkours.filter { it.value.checkpoints.isNotEmpty() || it.key == activeRouteName }
        if (parkours.isEmpty()) { chat("§cNo parkours saved."); return }
        chat("§6Parkours (active: §e$activeRouteName§6):")
        parkours.forEach { (name, route) ->
            val best = if (route.bestTimeMs == Long.MAX_VALUE) "§7no best" else "§a${formatTime(route.bestTimeMs)}"
            val active = if (name == activeRouteName) "§a✔ " else "  "
            chat("$active§e$name §7— ${route.checkpoints.size} checkpoints — $best")
        }
    }
    fun resetBest() { activeRoute.bestTimeMs = Long.MAX_VALUE; save(); chat("§cBest reset for §e$activeRouteName") }
    fun deleteParkour(name: String) {
        saveData.parkours.remove(name); save(); chat("§cDeleted parkour §e$name")
    }
    fun selectParkour(name: String) {
        activeRouteName = name
        FamilyConfigManager.config.parkour.activeParkour = name
        FamilyConfigManager.save()
        val exists = saveData.parkours.containsKey(name)
        chat(if (exists) "§aSelected parkour: §e$name" else "§aCreated new parkour: §e$name §7(use /faparkour add to add checkpoints)")
    }

    private fun formatTime(ms: Long): String {
        if (ms == Long.MAX_VALUE) return "--:--.---"
        return "%d:%02d.%03d".format(ms / 60000, (ms % 60000) / 1000, ms % 1000)
    }
    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal("§6[FA] $msg"), false)
        }
    }
}
