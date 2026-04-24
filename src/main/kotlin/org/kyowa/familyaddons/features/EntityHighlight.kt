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
import net.minecraft.entity.player.PlayerEntity
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()
    private var tick = 0

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

    /**
     * True if [entity] represents a real connected player and must NEVER be highlighted.
     *
     * On Hypixel SkyBlock, mob NPCs are spawned as PlayerEntity instances (full player skins,
     * custom AI). A real player can be told apart from an NPC because real players have an
     * entry in the tab list (PlayerListEntry); NPC mobs do not. This is the same check used
     * by SkyHanni and Odin to avoid hitting NPCs with anti-cheat-style filters.
     *
     * Returns false for non-player entities (mobs, animals, armor stands etc.).
     */
    private fun isRealPlayer(entity: Entity): Boolean {
        if (entity !is PlayerEntity) return false
        val handler = MinecraftClient.getInstance().networkHandler ?: return false
        // The local player always has a PlayerListEntry — covered correctly here.
        return handler.getPlayerListEntry(entity.uuid) != null
    }

    private fun resolveEntity(entity: Entity): Entity? {
        if (entity is ArmorStandEntity && entity.isInvisible) {
            val world = MinecraftClient.getInstance().world ?: return null
            val byId = world.getEntityById(entity.id - 1)
            // Reject the id-1 candidate if it's any real connected player (not just self).
            if (byId != null && byId !is ArmorStandEntity && !isRealPlayer(byId) && byId.isAlive) return byId
            val candidates = world.getEntitiesByClass(
                LivingEntity::class.java, entity.boundingBox.expand(0.5, 1.5, 0.5)
            ) { it !is ArmorStandEntity && !isRealPlayer(it) && it.isAlive }
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
            // Skip any real connected player up-front. This prevents matches like the search
            // term "dragon" highlighting a player named "dragonslayer213". NPC mobs that use
            // player skins (Hypixel's fake-player NPCs) pass this check because they are not
            // in the tab list — they will still be highlighted normally.
            if (isRealPlayer(entity)) return@forEach
            if (nameMatches(entity)) {
                // FIX: if resolveEntity returns null (nametag stand can't find its real mob
                // because the mob died this tick), skip entirely. The old `?: entity` fallback
                // would add the armor stand itself to `highlighted`, causing the tracer to
                // briefly snap to the stand's position before it despawns — visible flicker.
                val target = resolveEntity(entity) ?: return@forEach
                // Defensive: never highlight an invisible nametag stand directly.
                if (target is ArmorStandEntity && target.isInvisible) return@forEach
                // Defensive: resolveEntity already filters real players, but double-check in
                // case `entity` itself was a nameMatches-passing player (impossible after the
                // earlier guard, but cheap insurance).
                if (isRealPlayer(target)) return@forEach
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

            // Pick the closest N live+highlighted+in-range mobs each frame.
            // When a mob dies it leaves `highlighted` → instantly drops from this list.
            val targets = ArrayList<Entity>()
            for (entity in highlighted) {
                if (!entity.isAlive) continue
                // FIX: never run a tracer to an invisible nametag armor stand. Belt-and-braces
                // in case one ever makes it into `highlighted` through some other code path.
                if (entity is ArmorStandEntity) continue
                val dx = entity.x - camX
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - camY
                val dz = entity.z - camZ
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) targets.add(entity)
            }
            targets.sortBy { entity ->
                val dx = entity.x - camX
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - camY
                val dz = entity.z - camZ
                dx * dx + dy * dy + dz * dz
            }
            val picked = if (targets.size > count) targets.subList(0, count) else targets

            if (picked.isNotEmpty()) {
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

                for (entity in picked) {
                    val ex = entity.x.toFloat()
                    val ey = ((entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0).toFloat()
                    val ez = entity.z.toFloat()

                    val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
                    val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val nx = if (len > 0f) dx / len else 0f
                    val ny = if (len > 0f) dy / len else 0f
                    val nz = if (len > 0f) dz / len else 0f

                    buf.vertex(pose, sx, sy, sz)
                        .color(r, g, b, 1.0f)
                        .normal(pose, nx, ny, nz)
                    buf.vertex(pose, ex, ey, ez)
                        .color(r, g, b, 1.0f)
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