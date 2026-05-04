package org.kyowa.familyaddons.features

import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.mob.GiantEntity
import net.minecraft.entity.mob.ZombieEntity
import net.minecraft.entity.projectile.FishingBobberEntity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11

/**
 * Kuudra supply-crate ESP — drag radius circle + crate hitbox wireframe.
 *
 * Detection has been moved to [KuudraGiants] so the public [SupplyWaypoints]
 * feature can read the same giant list without scanning twice. This class
 * just consumes that data and renders.
 *
 * Whitelist gating: register() returns early if the player isn't allowed,
 * so this feature is fully inert (no event handlers) for non-whitelisted
 * users. The giant scan in [KuudraGiants] still runs if the public
 * SupplyWaypoints feature is on, but this class's rendering won't fire.
 *
 * Phase gating happens inside [KuudraGiants] — outside Phase 1 the giant
 * list is empty, so [hasCrates] returns false naturally.
 *
 * In-range color logic:
 *  - Crate switches to "in reach" color when player eye is within REACH_DIST
 *    of the closest point on the zombie's bounding box.
 *  - Drag switches to "in range" color when the player's fishing bobber is
 *    within DRAG_DIST of the crate AND the bobber Y is in 70..80. No rod
 *    out means never green.
 */
object KuudraCrateWaypoints {

    private const val REACH_DIST = 2.75
    private const val DRAG_DIST  = 4.75

    // Zombie must be within this many blocks of a crate to be drawn.
    private const val ZOMBIE_TO_CRATE_MAX = 3.0

    // Bobber Y must lie in this range for the drag to register.
    private const val BOBBER_Y_MIN = 70.0
    private const val BOBBER_Y_MAX = 80.0

    private const val CIRCLE_SEGMENTS = 48

    private fun cfg() = FamilyConfigManager.config.hidden
    private fun isInKuudra() = AutoRequeue.isInKuudra()

    fun hasCrates(): Boolean {
        if (!org.kyowa.familyaddons.Whitelist.isAllowed()) return false
        if (!cfg().crateWaypointsEnabled) return false
        if (!isInKuudra()) return false
        if (!KuudraPhase.isInP1()) return false
        return KuudraGiants.giants.isNotEmpty() || KuudraGiants.zombies.isNotEmpty()
    }

    /** Distance from player eye to closest point on the zombie's hitbox. */
    private fun reachDistanceTo(z: ZombieEntity): Double {
        val player = MinecraftClient.getInstance().player ?: return Double.MAX_VALUE
        val eye = player.getCameraPosVec(1f)
        val box = z.boundingBox.expand(0.1)
        val cx = eye.x.coerceIn(box.minX, box.maxX)
        val cy = eye.y.coerceIn(box.minY, box.maxY)
        val cz = eye.z.coerceIn(box.minZ, box.maxZ)
        val dx = eye.x - cx; val dy = eye.y - cy; val dz = eye.z - cz
        return Math.sqrt(dx*dx + dy*dy + dz*dz)
    }

    /**
     * Drag-range "in range" check. Requires fishing bobber to be near the
     * crate position with a sane Y. Returns false if no rod is out.
     */
    private fun bobberInDragRange(g: GiantEntity): Boolean {
        val player = MinecraftClient.getInstance().player ?: return false
        val hook: FishingBobberEntity = player.fishHook ?: return false
        val bobberPos = Vec3d(hook.x, hook.y, hook.z)
        if (bobberPos.y < BOBBER_Y_MIN || bobberPos.y > BOBBER_Y_MAX) return false
        return KuudraGiants.cratePosFor(g).distanceTo(bobberPos) <= DRAG_DIST
    }

    fun register() {
        // Whitelist gate — non-whitelisted users don't register any handlers,
        // so this feature is fully inert (not just config-hidden). Note that
        // KuudraGiants registers separately and may still scan if the public
        // SupplyWaypoints feature is enabled, but no rendering happens here.
        if (!org.kyowa.familyaddons.Whitelist.isAllowed()) {
            return
        }
        // No tick handler needed — KuudraGiants owns the scan. We only render.
    }

    /** Parse "chroma:alpha:r:g:b" → Float[4] (r,g,b,a) in 0..1. */
    private fun parseColor(s: String, fallback: FloatArray = floatArrayOf(1f, 1f, 0f, 1f)): FloatArray {
        return try {
            val p = s.split(":")
            floatArrayOf(
                p[2].toInt() / 255f,
                p[3].toInt() / 255f,
                p[4].toInt() / 255f,
                p[1].toInt() / 255f
            )
        } catch (e: Exception) { fallback }
    }

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        if (!hasCrates()) return
        val cfg = cfg()
        val mc = MinecraftClient.getInstance()
        val immediate = mc.bufferBuilders?.entityVertexConsumers ?: return

        val crateColor = parseColor(cfg.crateHitboxColor)
        val crateReach = parseColor(cfg.crateHitboxInReachColor)
        val dragColor = parseColor(cfg.dragHitboxColor)
        val dragRange = parseColor(cfg.dragHitboxInRangeColor)

        val camX = camera.pos.x
        val camY = camera.pos.y
        val camZ = camera.pos.z

        matrices.push()
        matrices.translate(-camX, -camY, -camZ)

        // ── Drag hitbox: horizontal circle at each giant's crate position ──
        if (cfg.showDragHitbox) {
            for (g in KuudraGiants.giants) {
                if (!g.isAlive) continue
                val pos = KuudraGiants.cratePosFor(g)
                val color = if (cfg.dragHitboxInRangeColorChange && bobberInDragRange(g)) dragRange else dragColor
                drawHorizontalCircle(matrices, immediate, pos, DRAG_DIST, color)
            }
        }

        // ── Crate hitbox: small wireframe box around each near-crate zombie ─
        if (cfg.showCrateHitbox) {
            for (z in KuudraGiants.zombies) {
                if (!z.isAlive) continue
                val zPos = Vec3d(z.x, z.y, z.z)
                val nearestCrateDist = KuudraGiants.giants.asSequence()
                    .filter { it.isAlive }
                    .map { KuudraGiants.cratePosFor(it).distanceTo(zPos) }
                    .minOrNull() ?: Double.MAX_VALUE
                if (nearestCrateDist > ZOMBIE_TO_CRATE_MAX) continue

                val color = if (cfg.crateHitboxReachColorChange && reachDistanceTo(z) <= REACH_DIST) crateReach else crateColor
                drawWireframeBox(matrices, immediate, z.boundingBox.expand(0.1), color)
            }
        }

        matrices.pop()
    }

    /** Wireframe outline of an AABB. Drawn with depth, then through walls at low alpha. */
    private fun drawWireframeBox(
        matrices: MatrixStack,
        immediate: VertexConsumerProvider.Immediate,
        box: Box,
        color: FloatArray
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]
        // Solid in front
        run {
            val buf = immediate.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), buf, box, r, g, b, a)
            immediate.draw(RenderLayer.getLines())
        }
        // Faded through walls
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        run {
            val buf = immediate.getBuffer(RenderLayer.getLines())
            VertexRendering.drawBox(matrices.peek(), buf, box, r, g, b, a * 0.3f)
            immediate.draw(RenderLayer.getLines())
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST)
    }

    /**
     * Horizontal circle (XZ plane) centered at `center`, given radius. Built
     * from line segments along the perimeter. Drawn solid in front, faded
     * through walls.
     */
    private fun drawHorizontalCircle(
        matrices: MatrixStack,
        immediate: VertexConsumerProvider.Immediate,
        center: Vec3d,
        radius: Double,
        color: FloatArray
    ) {
        val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

        fun emit(alpha: Float) {
            val buf = immediate.getBuffer(RenderLayer.getLines())
            val pose = matrices.peek()
            val cx = center.x.toFloat()
            val cy = center.y.toFloat()
            val cz = center.z.toFloat()
            val twoPi = (Math.PI * 2.0).toFloat()
            var prevX = (cx + radius).toFloat()
            var prevZ = cz
            for (i in 1..CIRCLE_SEGMENTS) {
                val angle = twoPi * i / CIRCLE_SEGMENTS
                val nx = (cx + radius * Math.cos(angle.toDouble())).toFloat()
                val nz = (cz + radius * Math.sin(angle.toDouble())).toFloat()
                val dx = nx - prevX; val dz = nz - prevZ
                val len = Math.sqrt((dx*dx + dz*dz).toDouble()).toFloat().coerceAtLeast(1e-4f)
                val nrmX = dx / len; val nrmZ = dz / len
                buf.vertex(pose, prevX, cy, prevZ).color(r, g, b, alpha).normal(pose, nrmX, 0f, nrmZ)
                buf.vertex(pose, nx, cy, nz).color(r, g, b, alpha).normal(pose, nrmX, 0f, nrmZ)
                prevX = nx; prevZ = nz
            }
            immediate.draw(RenderLayer.getLines())
        }

        emit(a)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        emit(a * 0.3f)
        GL11.glEnable(GL11.GL_DEPTH_TEST)
    }

    /** Debug dump for /fakuudra crates. */
    fun debugDump(): String {
        val sb = StringBuilder()
        val cfg = cfg()
        val chat = AutoRequeue.chatTriggerActive()
        val area = AutoRequeue.isInKuudraArea()
        val combined = isInKuudra()
        val phase1 = KuudraPhase.isInP1()
        val player = MinecraftClient.getInstance().player

        sb.append("§6[FA Kuudra] §7Flags: ")
            .append("§echat=").append(if (chat) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§earea=").append(if (area) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§ecombined=").append(if (combined) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§einP1=").append(if (phase1) "§atrue" else "§cfalse")
            .append("§7 ")
            .append("§eenabled=").append(if (cfg.crateWaypointsEnabled) "§atrue" else "§cfalse")
            .append("\n")

        val hookOut = player?.fishHook != null
        sb.append("§7Rod out: ").append(if (hookOut) "§ayes" else "§cno")
        if (hookOut && player != null) {
            val h = player.fishHook!!
            sb.append("§7 bobber @ §f${"%.1f".format(h.x)}, ${"%.1f".format(h.y)}, ${"%.1f".format(h.z)}")
        }
        sb.append("\n")

        val giants = KuudraGiants.giants
        val zombies = KuudraGiants.zombies
        sb.append("§7Detected: §a${giants.size}§7 giants, §a${zombies.size}§7 zombies\n")

        for (g in giants) {
            val pos = KuudraGiants.cratePosFor(g)
            val drag = if (player?.fishHook != null) {
                val h = player.fishHook!!
                pos.distanceTo(Vec3d(h.x, h.y, h.z))
            } else null
            sb.append("§a Giant @ §f${"%.1f".format(g.x)}, ${"%.1f".format(g.y)}, ${"%.1f".format(g.z)}")
                .append(" §7yaw=${"%.1f".format(g.yaw)}")
                .append(" §7crate≈${"%.1f".format(pos.x)}, ${"%.1f".format(pos.y)}, ${"%.1f".format(pos.z)}")
                .append(" §7drag=").append(if (drag != null) "%.2f".format(drag) else "§cno-rod")
                .append("\n")
        }
        for (z in zombies) {
            val zPos = Vec3d(z.x, z.y, z.z)
            val nearest = giants.asSequence()
                .filter { it.isAlive }
                .map { KuudraGiants.cratePosFor(it).distanceTo(zPos) }
                .minOrNull() ?: Double.MAX_VALUE
            val visible = nearest <= ZOMBIE_TO_CRATE_MAX
            sb.append(if (visible) "§b" else "§8")
                .append(" Zombie @ §f${"%.1f".format(z.x)}, ${"%.1f".format(z.y)}, ${"%.1f".format(z.z)}")
                .append(" §7nearestCrate=${"%.2f".format(nearest)}")
                .append(if (visible) " §a[shown]" else " §8[hidden]")
                .append(" §7reach=${"%.2f".format(reachDistanceTo(z))}\n")
        }

        if (giants.isEmpty() && zombies.isEmpty()) {
            sb.append("§e--- Broad scan (ignoring all gates) ---\n")
            val world = MinecraftClient.getInstance().world
            if (world == null || player == null) {
                sb.append("§c No world/player available.\n")
                return sb.toString()
            }
            val classCounts = HashMap<String, Int>()
            var totalNearby = 0
            for (entity in world.entities) {
                if (!entity.isAlive) continue
                val dx = entity.x - player.x
                val dz = entity.z - player.z
                if (dx*dx + dz*dz > 80.0 * 80.0) continue
                totalNearby++
                val key = entity.javaClass.simpleName
                classCounts[key] = (classCounts[key] ?: 0) + 1
            }
            sb.append("§7 Nearby entities (within 80 blocks): §a$totalNearby §7total\n")
            classCounts.entries
                .sortedByDescending { it.value }
                .take(15)
                .forEach { (cls, count) ->
                    sb.append("§7  §f$count §7× §e$cls\n")
                }
        }

        return sb.toString()
    }
}