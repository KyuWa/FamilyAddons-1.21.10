package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()
    private var tick = 0

    // Grace-period cache for tracer targets — prevents flicker when a mob dies and its
    // nameplate armor stand briefly resolves to a different nearby entity, or when a mob
    // falls out of the highlight list for a frame or two during death animation.
    private data class TracerState(var posX: Double, var posY: Double, var posZ: Double, var lastSeenMs: Long)
    private val tracerStates = mutableMapOf<Int, TracerState>()
    private const val TRACER_GRACE_MS = 500L

    private fun shouldScan(): Boolean {
        if (FamilyConfigManager.config.highlight.enabled) return true
        val bestiary = FamilyConfigManager.config.bestiary
        if (bestiary.zoneHighlightEnabled && bestiary.bestiaryZone != 0) return true
        if (bestiary.mobName.isNotBlank()) return true
        return false
    }

    private fun getNames(): List<String> {
        val names = mutableListOf<String>()
        if (FamilyConfigManager.config.highlight.enabled) {
            FamilyConfigManager.config.highlight.mobNames
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { if (it !in names) names.add(it) }
        }
        val bestiaryMob = FamilyConfigManager.config.bestiary.mobName.trim().lowercase()
        if (bestiaryMob.isNotBlank() && bestiaryMob !in names) names.add(bestiaryMob)
        if (FamilyConfigManager.config.bestiary.zoneHighlightEnabled) {
            BestiaryZoneHighlight.activeMobNames.forEach { mob ->
                val lower = mob.lowercase()
                if (lower.isNotBlank() && lower !in names) names.add(lower)
            }
        }
        return names
    }

    private fun nameMatches(entity: Entity): Boolean {
        val names = getNames()
        if (names.isEmpty()) return false
        val name = entity.name.string.replace(COLOR_CODE_REGEX, "").lowercase()
        val customName = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()
        return names.any { n -> name.contains(n) || customName?.contains(n) == true }
    }

    private fun resolveEntity(entity: Entity): Entity? {
        if (entity is ArmorStandEntity && entity.isInvisible) {
            val world = MinecraftClient.getInstance().world ?: return null
            val player = MinecraftClient.getInstance().player
            val byId = world.getEntityById(entity.id - 1)
            if (byId != null && byId !is ArmorStandEntity && byId != player && byId.isAlive) return byId
            val candidates = world.getEntitiesByClass(
                LivingEntity::class.java, entity.boundingBox.expand(0.5, 1.5, 0.5)
            ) { it !is ArmorStandEntity && it != player && it.isAlive }
            return candidates.minByOrNull { val dx = it.x - entity.x; val dz = it.z - entity.z; dx*dx + dz*dz }
        }
        return entity
    }

    fun getOutlineColor(entity: Entity): Int {
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return 0
        if (config.drawingStyle != 1) return 0
        if (entity !in highlighted) return 0
        return try {
            val parts = config.color.split(":")
            val r = parts[2].toInt(); val g = parts[3].toInt(); val b = parts[4].toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (e: Exception) { 0xFFFF0000.toInt() }
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!shouldScan()) {
                if (highlighted.isNotEmpty()) highlighted.clear()
                return@register
            }
            val interval = FamilyConfigManager.config.utilities.highlightRescanInterval.toInt().coerceIn(1, 20)
            if (tick++ % interval != 0) return@register
            rescan()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> highlighted.clear() }
    }

    fun rescan() {
        highlighted.clear()
        val world = MinecraftClient.getInstance().world ?: return
        val names = getNames()
        if (names.isEmpty()) return
        world.entities.forEach { entity ->
            if (!entity.isAlive) return@forEach
            if (nameMatches(entity)) {
                val target = resolveEntity(entity) ?: entity
                if (target.isAlive) highlighted.add(target)
            }
        }
    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return
        if (highlighted.isEmpty()) return

        val immediate = MinecraftClient.getInstance()
            .bufferBuilders?.entityVertexConsumers ?: return

        val (r, g, b) = try {
            val parts = config.color.split(":")
            Triple(parts[2].toInt() / 255f, parts[3].toInt() / 255f, parts[4].toInt() / 255f)
        } catch (e: Exception) { Triple(1f, 0f, 0f) }

        val camX = camera.pos.x
        val camY = camera.pos.y
        val camZ = camera.pos.z

        matrices.push()
        matrices.translate(-camX, -camY, -camZ)
        highlighted.removeIf { !it.isAlive }

        // ── ESP boxes ─────────────────────────────────────────────────
        if (config.drawingStyle == 0) {
            fun drawBoxes(alpha: Float) {
                val buf = immediate.getBuffer(RenderLayer.getLines())
                for (entity in highlighted) {
                    if (!entity.isAlive) continue
                    VertexRendering.drawBox(matrices.peek(), buf, entity.boundingBox, r, g, b, alpha)
                }
                (immediate as? VertexConsumerProvider.Immediate)?.draw(RenderLayer.getLines())
            }
            drawBoxes(1.0f)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            drawBoxes(0.3f)
            GL11.glEnable(GL11.GL_DEPTH_TEST)
        }

        // ── Tracer lines ──────────────────────────────────────────────
        // The start point is offset FORWARD from the camera by a small amount so the line
        // isn't clipped by the near plane. The forward vector is computed from camera yaw/pitch.
        // Result: line visually starts at crosshair (because the offset point is directly in
        // front of the camera, projecting to screen center) and extends to the mob.
        if (config.tracerEnabled) {
            val count = config.tracerCount.toInt().coerceIn(1, 20)
            val maxBlocks = config.tracerChunkRange.toDouble() * 16.0
            val maxDistSq = maxBlocks * maxBlocks
            val now = System.currentTimeMillis()

            // Current-frame candidates: alive, in range, closest N
            val currentCandidates = highlighted
                .filter { entity ->
                    if (!entity.isAlive) return@filter false
                    val dx = entity.x - camX
                    val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - camY
                    val dz = entity.z - camZ
                    (dx * dx + dy * dy + dz * dz) <= maxDistSq
                }
                .sortedBy { entity ->
                    val dx = entity.x - camX
                    val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - camY
                    val dz = entity.z - camZ
                    dx * dx + dy * dy + dz * dz
                }
                .take(count)

            // Refresh cached state for current candidates
            for (entity in currentCandidates) {
                val midY = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0
                val existing = tracerStates[entity.id]
                if (existing != null) {
                    existing.posX = entity.x
                    existing.posY = midY
                    existing.posZ = entity.z
                    existing.lastSeenMs = now
                } else {
                    tracerStates[entity.id] = TracerState(entity.x, midY, entity.z, now)
                }
            }

            // Expire stale states
            tracerStates.entries.removeIf { (_, s) -> now - s.lastSeenMs > TRACER_GRACE_MS }

            // Cap cache size so it can't grow unbounded when many mobs cycle through range
            if (tracerStates.size > count * 3) {
                val keepIds = tracerStates.entries
                    .sortedByDescending { it.value.lastSeenMs }
                    .take(count * 3)
                    .map { it.key }
                    .toSet()
                tracerStates.keys.retainAll(keepIds)
            }

            if (tracerStates.isNotEmpty()) {
                // Camera forward vector (avoid near-plane clipping by offsetting start)
                val yawRad = Math.toRadians(camera.yaw.toDouble())
                val pitchRad = Math.toRadians(camera.pitch.toDouble())
                val fwdX = -Math.sin(yawRad) * Math.cos(pitchRad)
                val fwdY = -Math.sin(pitchRad)
                val fwdZ = Math.cos(yawRad) * Math.cos(pitchRad)

                val startOffset = 0.5
                val sx = (camX + fwdX * startOffset).toFloat()
                val sy = (camY + fwdY * startOffset).toFloat()
                val sz = (camZ + fwdZ * startOffset).toFloat()

                GL11.glDisable(GL11.GL_DEPTH_TEST)
                val buf = immediate.getBuffer(RenderLayer.getLines())
                val pose = matrices.peek()

                for (state in tracerStates.values) {
                    // Fade alpha out over the grace period for a smooth transition
                    val age = now - state.lastSeenMs
                    val alpha = if (age <= 0L) 1.0f
                    else (1.0f - age.toFloat() / TRACER_GRACE_MS.toFloat()).coerceIn(0.0f, 1.0f)

                    val ex = state.posX.toFloat()
                    val ey = state.posY.toFloat()
                    val ez = state.posZ.toFloat()

                    val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
                    val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val nx = if (len > 0f) dx / len else 0f
                    val ny = if (len > 0f) dy / len else 0f
                    val nz = if (len > 0f) dz / len else 0f

                    buf.vertex(pose, sx, sy, sz)
                        .color(r, g, b, alpha)
                        .normal(pose, nx, ny, nz)
                    buf.vertex(pose, ex, ey, ez)
                        .color(r, g, b, alpha)
                        .normal(pose, nx, ny, nz)
                }

                (immediate as? VertexConsumerProvider.Immediate)?.draw(RenderLayer.getLines())
                GL11.glEnable(GL11.GL_DEPTH_TEST)
            }
        }

        matrices.pop()
    }

    fun hasHighlighted() = highlighted.isNotEmpty() && shouldScan()
}