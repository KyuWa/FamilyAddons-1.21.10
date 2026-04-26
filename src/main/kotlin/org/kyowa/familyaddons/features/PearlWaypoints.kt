package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.item.EnderPearlItem
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pearl aim waypoints for Solo Kuudra phase 1 (supply pickup).
 *
 * For each of the six supply piles, we solve the inverse trajectory problem:
 * given the player's current position and a fixed pile XZ, find the pitch
 * that — combined with the player's current yaw direction toward the pile —
 * lands a pearl on that pile.
 *
 * Why this works without calibration constants (unlike the original CT
 * version): we use the same per-tick forward simulation that PearlTimer uses,
 * which is the actual physics the server runs. The closed-form ballistic
 * equation IS approximate (ignores 0.99 drag), so any code using it has to
 * compensate empirically. The simulator is exact, so we just sweep pitches
 * until we find the one that lands on target.
 *
 * Once solved, we render a small box at the aim point in world space — the
 * direction from player eyes through that box matches the solved (yaw, pitch).
 * Player aims crosshair at box → throws pearl → pearl lands on pile.
 *
 * Pile occupancy: piles already filled have a "SUPPLIES RECEIVED" armor stand
 * floating above them. We scan for those each tick window and hide the
 * waypoint for any occupied pile.
 */
object PearlWaypoints {

    // ── Constants ─────────────────────────────────────────────────────

    /** The six supply piles — fixed XZ coordinates in Kuudra's Hollow. */
    data class Pile(val pre: Int, val name: String, val x: Double, val z: Double)

    private val PILES = listOf(
        Pile(1, "x",      -106.0, -113.0),
        Pile(2, "xc",     -110.0, -106.0),
        Pile(4, "equals",  -98.0,  -99.0),
        Pile(5, "slash",  -106.0,  -99.0),
        Pile(6, "tri",     -94.0, -106.0),
        Pile(7, "shop",    -98.0, -113.0),
    )

    // Pearl physics — must match server-side and PearlTimer's predictor.
    private const val PEARL_SPEED = 1.5
    private const val DRAG = 0.99
    private const val GRAVITY = 0.03

    // Solver search range. Pitches outside this range either send the pearl
    // straight up or straight down — neither lands on a horizontal pile.
    private const val PITCH_MIN_DEG = -60.0
    private const val PITCH_MAX_DEG = 30.0
    private const val PITCH_COARSE_STEP_DEG = 1.0
    private const val PITCH_REFINE_ITERS = 6

    // Hard cap on simulation length per pitch candidate. 100 ticks = 5 s.
    private const val SIM_MAX_TICKS = 100

    // How far the simulated landing can be from the pile XZ to count as a hit.
    private const val LANDING_TOLERANCE_BLOCKS = 1.5

    // Resolve cadence — recomputing every frame is wasteful.
    private const val RESOLVE_INTERVAL_TICKS = 5

    // Occupancy scan cadence and threshold.
    private const val OCCUPANCY_INTERVAL_TICKS = 10
    private const val OCCUPANCY_DIST_SQ = 6.0 * 6.0

    // Aim point box render size (full edge length).
    private const val BOX_SIZE = 0.6

    // ── State ─────────────────────────────────────────────────────────

    /** A solved aim point for one pile. */
    data class AimPoint(
        val pile: Pile,
        val aimX: Double,
        val aimY: Double,
        val aimZ: Double,
        val reachable: Boolean
    )

    @Volatile private var activeAimPoints: List<AimPoint> = emptyList()

    private val pileOccupied = HashMap<Int, Boolean>().apply {
        for (p in PILES) put(p.pre, false)
    }

    private var ticksSinceResolve = RESOLVE_INTERVAL_TICKS
    private var ticksSinceOccupancy = OCCUPANCY_INTERVAL_TICKS

    fun hasAimPoints(): Boolean = activeAimPoints.isNotEmpty()

    // ── Public render hook (called from WorldRendererMixin) ───────────

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        val points = activeAimPoints
        if (points.isEmpty()) return

        val client = MinecraftClient.getInstance()
        val cam = camera.pos
        val immediate = client.bufferBuilders?.entityVertexConsumers ?: return

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Pass 1: depth-tested wireframe (visible color when in line of sight).
        for (ap in points) {
            val half = BOX_SIZE / 2.0
            val box = Box(
                ap.aimX - half, ap.aimY - half, ap.aimZ - half,
                ap.aimX + half, ap.aimY + half, ap.aimZ + half
            )
            val (r, g, b) = if (ap.reachable) Triple(0.2f, 1.0f, 0.4f)
            else Triple(1.0f, 0.3f, 0.3f)
            VertexRendering.drawBox(
                matrices.peek(),
                immediate.getBuffer(RenderLayer.getLines()),
                box, r, g, b, 1.0f
            )
        }
        immediate.draw(RenderLayer.getLines())

        // Pass 2: through-walls translucent (so it stays visible if the player
        // turns away or steps behind a pillar).
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers
        if (immediate2 != null) {
            for (ap in points) {
                val half = BOX_SIZE / 2.0
                val box = Box(
                    ap.aimX - half, ap.aimY - half, ap.aimZ - half,
                    ap.aimX + half, ap.aimY + half, ap.aimZ + half
                )
                val (r, g, b) = if (ap.reachable) Triple(0.2f, 1.0f, 0.4f)
                else Triple(1.0f, 0.3f, 0.3f)
                VertexRendering.drawBox(
                    matrices.peek(),
                    immediate2.getBuffer(RenderLayer.getLines()),
                    box, r, g, b, 0.35f
                )
            }
            immediate2.draw(RenderLayer.getLines())
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun register() {
        // Driven by the server-tick signal — same approach as PearlTimer and
        // GorillaTactics. Keeps cadence synced to actual game time and
        // automatically pauses during server lag.
        ServerTickTracker.onTick {
            ticksSinceResolve++
            ticksSinceOccupancy++

            if (!shouldRender()) {
                if (activeAimPoints.isNotEmpty()) activeAimPoints = emptyList()
                return@onTick
            }

            if (ticksSinceOccupancy >= OCCUPANCY_INTERVAL_TICKS) {
                ticksSinceOccupancy = 0
                updatePileOccupancy()
            }

            if (ticksSinceResolve >= RESOLVE_INTERVAL_TICKS) {
                ticksSinceResolve = 0
                resolveAll()
            }
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            activeAimPoints = emptyList()
            for (p in PILES) pileOccupied[p.pre] = false
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            activeAimPoints = emptyList()
        }
    }

    // ── Gating ────────────────────────────────────────────────────────

    /**
     * Render only when:
     *  - the feature is enabled,
     *  - the player is in Kuudra's Hollow, and
     *  - the player is holding an ender pearl.
     *
     * The held-pearl gate is a cheap stand-in for "phase 1": you only carry
     * pearls during the supply-pickup phase. Saves the trouble of phase
     * detection while giving the right behavior in practice.
     */
    private fun shouldRender(): Boolean {
        if (!FamilyConfigManager.config.soloKuudra.pearlWaypoints) return false
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return false

        // Held-pearl check (main hand or off hand).
        val main = player.mainHandStack
        val off = player.offHandStack
        val holdingPearl = (main.item is EnderPearlItem) || (off.item is EnderPearlItem)
        if (!holdingPearl) return false

        // Subarea check — looks for "Area: Kuudra" in the tab list.
        if (!isInKuudra()) return false

        return true
    }

    private fun isInKuudra(): Boolean {
        return try {
            val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return false
            for (entry in tabList) {
                val name = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                if (name.startsWith("Area:") && name.contains("Kuudra", ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) { false }
    }

    // ── Pile occupancy ────────────────────────────────────────────────

    private fun updatePileOccupancy() {
        for (p in PILES) pileOccupied[p.pre] = false

        val world = MinecraftClient.getInstance().world ?: return

        for (entity in world.entities) {
            if (entity !is ArmorStandEntity) continue
            // Custom name check first (cheaper) — fall back to display name.
            val nameComponent = entity.customName ?: continue
            val plain = nameComponent.string.replace(COLOR_CODE_REGEX, "")
            if (!plain.contains("SUPPLIES RECEIVED")) continue

            val ex = entity.x
            val ez = entity.z
            var bestPre = -1
            var bestDistSq = Double.POSITIVE_INFINITY
            for (p in PILES) {
                val dx = ex - p.x
                val dz = ez - p.z
                val d2 = dx * dx + dz * dz
                if (d2 < bestDistSq) {
                    bestDistSq = d2
                    bestPre = p.pre
                }
            }
            if (bestPre != -1 && bestDistSq <= OCCUPANCY_DIST_SQ) {
                pileOccupied[bestPre] = true
            }
        }
    }

    // ── Trajectory solver ─────────────────────────────────────────────

    private fun resolveAll() {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: run { activeAimPoints = emptyList(); return }
        val world = client.world ?: run { activeAimPoints = emptyList(); return }

        val px = player.x
        val py = player.eyeY
        val pz = player.z

        val results = ArrayList<AimPoint>(PILES.size)
        for (pile in PILES) {
            if (pileOccupied[pile.pre] == true) continue

            // Resolve target Y: the ground at the pile XZ. Raycast from far
            // above straight down. If we miss (open air column), fall back to
            // a sensible default of y=91, which is the supply pad floor in
            // the standard Kuudra arena.
            val targetY = findGroundY(pile.x, pile.z) ?: 91.0

            // The yaw from the player toward the pile XZ — solver uses this
            // direction; pitch is what we sweep.
            val dx = pile.x - px
            val dz = pile.z - pz
            val horizDist = sqrt(dx * dx + dz * dz)
            if (horizDist < 0.5) continue  // Standing on top of pile already.

            val solved = solvePitch(player.yaw, px, py, pz, pile, targetY)
            if (solved == null) {
                // No pitch in our range produces a landing within tolerance.
                // Compute an aim point anyway so the user sees *something*,
                // but mark unreachable.
                val fallbackY = py + 0.5
                results.add(AimPoint(pile, pile.x, fallbackY, pile.z, reachable = false))
                continue
            }

            // Compose the in-world aim point. We use the actual horizontal
            // direction from player to pile (XZ), not the player's current
            // yaw — because the visible aim box represents WHERE TO LOOK,
            // and "where to look" is along the pile direction.
            val pitchRad = Math.toRadians(solved)
            val aimY = py + tan(-pitchRad) * horizDist
            // Note: pitch is inverted in MC convention (negative = looking up).
            // We want aim point to be ABOVE eye level when pearl is thrown up,
            // so we flip the sign of the pitch in the height term.
            //
            // Sanity check: solver returns pitch in MC convention where
            // negative = up. tan(-pitch) is positive when pitch is negative,
            // so aim point is above eye — correct.

            results.add(AimPoint(pile, pile.x, aimY, pile.z, reachable = true))
        }

        activeAimPoints = results
    }

    /**
     * Find the highest solid block at (x, z) by raycasting straight down from
     * y=200. Returns the y-coordinate of the top surface, or null if nothing
     * is hit.
     */
    private fun findGroundY(x: Double, z: Double): Double? {
        val world = MinecraftClient.getInstance().world ?: return null
        val player = MinecraftClient.getInstance().player ?: return null
        val from = Vec3d(x, 200.0, z)
        val to = Vec3d(x, -64.0, z)
        val hit = world.raycast(
            RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
            )
        )
        if (hit.type != HitResult.Type.BLOCK) return null
        return hit.pos.y
    }

    /**
     * Sweep pitch from PITCH_MIN_DEG to PITCH_MAX_DEG, simulating each, and
     * return the pitch (in MC convention, negative = up) whose simulated
     * landing is closest to the pile XZ. Returns null if no candidate lands
     * within LANDING_TOLERANCE_BLOCKS.
     *
     * Two-stage: coarse 1° sweep, then ±0.5° binary refinement around the
     * best coarse candidate.
     */
    private fun solvePitch(
        yawDeg: Float,
        px: Double, py: Double, pz: Double,
        pile: Pile,
        targetY: Double
    ): Double? {
        val yawRad = Math.toRadians(yawDeg.toDouble())

        // Pearl spawn offset (matches vanilla ProjectileEntity offset and
        // PearlTimer's predictor).
        val ox = -cos(yawRad) * 0.16
        val oz = -sin(yawRad) * 0.16
        val startX = px + ox
        val startY = py - 0.1
        val startZ = pz + oz

        // For the YAW component of the launch direction, we want to point
        // straight at the pile in XZ — that's what the solver assumes. Pitch
        // is what we vary.
        //
        // We do NOT use the player's actual yaw here, because they might be
        // looking elsewhere. The solver's job is to find aim coords; the
        // visual box at those coords *implicitly* tells the player which yaw
        // to use.
        val dx = pile.x - startX
        val dz = pile.z - startZ
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist < 1e-3) return null
        val dirX = dx / horizDist
        val dirZ = dz / horizDist

        // Coarse sweep.
        var bestPitch = Double.NaN
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestWithinTolerance = false

        var pitchDeg = PITCH_MIN_DEG
        while (pitchDeg <= PITCH_MAX_DEG + 1e-6) {
            val landDistSq = simulateLandingDistSq(
                startX, startY, startZ,
                dirX, dirZ, pitchDeg,
                pile, targetY
            )
            if (landDistSq != null && landDistSq < bestDistSq) {
                bestDistSq = landDistSq
                bestPitch = pitchDeg
            }
            pitchDeg += PITCH_COARSE_STEP_DEG
        }

        if (bestPitch.isNaN()) return null

        // Refine: shrinking ±range around bestPitch.
        var refineRange = PITCH_COARSE_STEP_DEG
        repeat(PITCH_REFINE_ITERS) {
            val candidates = doubleArrayOf(bestPitch - refineRange, bestPitch + refineRange)
            for (c in candidates) {
                val d2 = simulateLandingDistSq(
                    startX, startY, startZ,
                    dirX, dirZ, c,
                    pile, targetY
                ) ?: continue
                if (d2 < bestDistSq) {
                    bestDistSq = d2
                    bestPitch = c
                }
            }
            refineRange *= 0.5
        }

        bestWithinTolerance = bestDistSq <= LANDING_TOLERANCE_BLOCKS * LANDING_TOLERANCE_BLOCKS
        return if (bestWithinTolerance) bestPitch else null
    }

    /**
     * Simulate one trajectory and return squared XZ distance from landing
     * point to pile. Returns null if the pearl doesn't terminate within
     * SIM_MAX_TICKS (e.g. flew off into open air).
     *
     * The simulation uses the same per-tick math as PearlTimer's predictor
     * and the actual server.
     */
    private fun simulateLandingDistSq(
        startX: Double, startY: Double, startZ: Double,
        dirX: Double, dirZ: Double, pitchDeg: Double,
        pile: Pile, targetY: Double
    ): Double? {
        val world = MinecraftClient.getInstance().world ?: return null
        val player = MinecraftClient.getInstance().player ?: return null

        // Convert pitch to a vertical component. MC convention: negative
        // pitch = looking up. So pearl with pitch=-30° has positive Y motion.
        // The horizontal component scales with cos(pitch).
        val pitchRad = Math.toRadians(pitchDeg)
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)

        // Direction vector: (dirX*cosP, -sinP, dirZ*cosP). Length is 1 by
        // construction (since dirX² + dirZ² = 1 and cos² + sin² = 1).
        var mx = dirX * cosP * PEARL_SPEED
        var my = -sinP * PEARL_SPEED
        var mz = dirZ * cosP * PEARL_SPEED

        var x = startX
        var y = startY
        var z = startZ

        for (tick in 1..SIM_MAX_TICKS) {
            val nx = x + mx
            val ny = y + my
            val nz = z + mz

            val hit = world.raycast(
                RaycastContext(
                    Vec3d(x, y, z),
                    Vec3d(nx, ny, nz),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
                )
            )
            if (hit.type == HitResult.Type.BLOCK && hit is BlockHitResult) {
                val landX = hit.pos.x
                val landZ = hit.pos.z
                val ddx = landX - pile.x
                val ddz = landZ - pile.z
                return ddx * ddx + ddz * ddz
            }

            x = nx; y = ny; z = nz
            mx *= DRAG
            my = (my - GRAVITY) * DRAG
            mz *= DRAG
        }
        return null
    }
}