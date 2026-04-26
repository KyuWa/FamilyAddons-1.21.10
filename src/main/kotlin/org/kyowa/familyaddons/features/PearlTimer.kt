package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.projectile.thrown.EnderPearlEntity
import net.minecraft.item.EnderPearlItem
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pearl land-time timer for Hypixel SkyBlock.
 *
 * Approach (prediction + entity tracking + per-tick collision raycast):
 *
 *  1. On right-click, simulate the pearl's flight using the same physics
 *     constants the vanilla server uses (matches Odin's Trajectories module):
 *
 *       - initial speed 1.5 b/t
 *       - per tick: motion = (motion.x * 0.99, (motion.y - 0.03) * 0.99, motion.z * 0.99)
 *
 *     This gives an immediate visible estimate so the user sees feedback the
 *     instant they click.
 *
 *  2. Within ~10 ticks, find the spawned EnderPearlEntity owned by the local
 *     player (matching by UUID, not reference equality — the owner field can
 *     be null on the client for the first few ticks while the tracker data
 *     arrives) and bind it to our timer entry.
 *
 *  3. Each tick once bound, take the entity's previous position and current
 *     position and raycast that segment against blocks AND nearby entity
 *     bounding boxes. If we hit something, the pearl just landed THIS tick —
 *     end the timer immediately.
 *
 *     This is critical because:
 *       - Server-side entity removal arrives 1-3 ticks late on the client
 *         (network latency), so waiting for `!isAlive` ends late.
 *       - Long throws can take the pearl out of client-side entity tracking
 *         range. `getEntityById` then returns null and the OLD code treated
 *         that as collision — ending the timer 3-5 ticks early. We now
 *         distinguish unload from death by checking the last known position.
 *
 *  4. If the entity is unloaded mid-flight (long throws across chunks), we
 *     fall back to extending the timer with a fresh local simulation from the
 *     last known position/velocity rather than ending early.
 *
 * Multiple pearls in flight are stacked in a list and displayed
 * "Pearl 1: 1.20s / Pearl 2: 0.85s" (or in ticks) in throw order.
 */
object PearlTimer {

    // Hard cap on how far we'll simulate. ~120 ticks = 6s, comfortably longer
    // than any reasonable pearl arc.
    private const val SIM_RANGE_TICKS = 120

    // Minimum ticks between accepted throws — prevents 5 timers from a single
    // right-click spam where only 1 pearl actually leaves the player.
    private const val THROW_DEBOUNCE_TICKS = 2

    // How long to wait for the EnderPearlEntity to appear in the world before
    // falling back to pure prediction.
    private const val BIND_TIMEOUT_TICKS = 10

    // Hold "0.00s" briefly after landing for visual confirmation. 200 ms.
    private const val POST_LAND_HOLD_TICKS = 4

    // If a tracked entity vanishes from getEntityById, AND its last known
    // position was within this distance of the player, it was almost certainly
    // a real death (collision). Beyond this distance, the more likely cause
    // is that it left client-side entity tracking range — we then continue
    // with a local simulation instead of ending early.
    private const val DEATH_VS_UNLOAD_THRESHOLD_BLOCKS = 64.0

    private enum class State { PENDING_BIND, TRACKING, PURE_PREDICTION, LANDED }

    private data class PearlEntry(
        val id: Int,
        var remainingTicks: Int,
        var ticksSinceThrow: Int = 0,
        var state: State = State.PENDING_BIND,
        var boundEntityId: Int = -1,
        var lastPos: Vec3d? = null,
        var lastVel: Vec3d? = null,
        var lastSeenAge: Int = 0,
        var postLandHold: Int = 0
    )

    @Volatile private var nextLabel = 1
    private val pearls = ArrayList<PearlEntry>()
    private var ticksSinceLastThrow = THROW_DEBOUNCE_TICKS

    // HUD editor preview text variants.
    const val PREVIEW_TEXT_SECONDS_1 = "§dPearl 1 §f1.20s"
    const val PREVIEW_TEXT_SECONDS_2 = "§dPearl 2 §f0.85s"
    const val PREVIEW_TEXT_TICKS_1 = "§dPearl 1 §f24t"
    const val PREVIEW_TEXT_TICKS_2 = "§dPearl 2 §f17t"

    fun previewLine1(): String =
        if (FamilyConfigManager.config.soloKuudra.pearlDisplayUnit == 1) PREVIEW_TEXT_TICKS_1
        else PREVIEW_TEXT_SECONDS_1

    fun previewLine2(): String =
        if (FamilyConfigManager.config.soloKuudra.pearlDisplayUnit == 1) PREVIEW_TEXT_TICKS_2
        else PREVIEW_TEXT_SECONDS_2

    fun getScale(): Float = FamilyConfigManager.config.soloKuudra.pearlTimerHudScale
        .toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1.0f

    fun resolvePos(sw: Int, sh: Int, scale: Float, textWidth: Int): Pair<Int, Int> {
        val cfg = FamilyConfigManager.config.soloKuudra
        return if (cfg.pearlTimerHudX == -1 || cfg.pearlTimerHudY == -1) {
            val x = ((sw - textWidth * scale) / 2f).toInt()
            val y = (sh / 2f + 60f).toInt()
            x to y
        } else {
            cfg.pearlTimerHudX to cfg.pearlTimerHudY
        }
    }

    /** Spirit pearls are wings, not throwable pearls — exclude them. */
    private fun isThrowablePearl(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.item !is EnderPearlItem) return false
        val name = stack.name?.string ?: return true
        if (name.contains("Spirit", ignoreCase = true)) return false
        return true
    }

    /**
     * Predict total flight ticks from current player pose. Used both at throw
     * time (initial estimate) and as a continuation when an entity unloads.
     */
    private fun predictLandingTicks(
        startPos: Vec3d,
        startMotion: Vec3d
    ): Int? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val world = client.world ?: return null

        var pos = startPos
        var motion = startMotion

        for (tick in 1..SIM_RANGE_TICKS) {
            val nextPos = pos.add(motion)

            val hit = world.raycast(
                RaycastContext(
                    pos,
                    nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
                )
            )
            if (hit.type == HitResult.Type.BLOCK && hit is BlockHitResult) {
                val segLen = motion.length()
                val toImpact = pos.distanceTo(hit.pos)
                val frac = if (segLen > 1e-6) (toImpact / segLen) else 1.0
                return ((tick - 1) + frac).coerceAtLeast(0.0)
                    .let { ceil(it).toInt().coerceAtLeast(1) }
            }

            pos = nextPos
            motion = Vec3d(motion.x * 0.99, (motion.y - 0.03) * 0.99, motion.z * 0.99)
        }
        return null
    }

    /** Initial throw estimate — derives start pos/motion from current player pose. */
    private fun predictInitialLandingTicks(): Int? {
        val player = MinecraftClient.getInstance().player ?: return null
        val yawRad = Math.toRadians(player.yaw.toDouble())
        val ox = -cos(yawRad) * 0.16
        val oz = -sin(yawRad) * 0.16
        val startPos = Vec3d(player.x + ox, player.eyeY - 0.1, player.z + oz)
        val startMotion = lookVector(player.yaw, player.pitch).normalize().multiply(1.5)
        return predictLandingTicks(startPos, startMotion)
    }

    private fun lookVector(yaw: Float, pitch: Float): Vec3d {
        val f = -cos(-pitch * 0.017453292)
        return Vec3d(
            sin(-yaw * 0.017453292 - Math.PI) * f,
            sin(-pitch * 0.017453292).toDouble(),
            cos(-yaw * 0.017453292 - Math.PI) * f
        )
    }

    /**
     * Bind any unbound EnderPearlEntities owned by the local player to our
     * PENDING_BIND entries.
     *
     * The owner check uses ownerUuid rather than `entity.owner !== player`
     * because `entity.owner` does a UUID lookup that returns null until the
     * server tracker pushes the owner UUID — which can take 1-2 ticks. Using
     * the UUID directly lets us bind on the very tick the entity arrives.
     */
    private fun tryBindPearls() {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val world = client.world ?: return

        val pending = pearls.filter { it.state == State.PENDING_BIND }
        if (pending.isEmpty()) return

        val alreadyBoundIds = pearls.mapNotNullTo(HashSet()) {
            if (it.boundEntityId != -1) it.boundEntityId else null
        }
        val candidates = ArrayList<EnderPearlEntity>()
        for (entity in world.entities) {
            if (entity !is EnderPearlEntity) continue
            if (entity.id in alreadyBoundIds) continue
            // Match by UUID against the entity's tracked owner UUID.
            val ownerUuid = entity.ownerUuid
            if (ownerUuid != null && ownerUuid != player.uuid) continue
            // If ownerUuid is null but the entity is brand new and very close
            // to us, it's almost certainly ours — accept it speculatively.
            if (ownerUuid == null && (entity.age > 4 || entity.distanceTo(player) > 8.0)) continue
            candidates.add(entity)
        }
        if (candidates.isEmpty()) return

        // Newest entity (lowest age) is most likely the most recent throw.
        candidates.sortBy { it.age }

        val toBind = minOf(pending.size, candidates.size)
        // Oldest pending entry binds to oldest candidate (highest age) so
        // throw-order labels stay consistent.
        for (i in 0 until toBind) {
            val entry = pending[i]
            val pearl = candidates[candidates.size - 1 - i]
            entry.boundEntityId = pearl.id
            entry.state = State.TRACKING
            entry.lastPos = pearl.pos
            entry.lastVel = pearl.velocity
            entry.lastSeenAge = pearl.age
        }
    }

    /**
     * Per-tick collision check: did the pearl just hit anything between its
     * last position and current position? Returns true on landing.
     *
     * This catches collisions on the same tick the server sees them, which
     * fixes both:
     *   - The "off by 3-5 ticks late" bug (waiting for entity removal packet)
     *   - The "off by 3-5 ticks early on long throws" bug (entity unloads
     *     out of view distance and we used to treat that as collision)
     */
    private fun didPearlLand(entity: EnderPearlEntity, prevPos: Vec3d): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        val curPos = entity.pos
        if (prevPos.squaredDistanceTo(curPos) < 1e-6) {
            // Entity didn't move this tick. On a moving projectile, that means
            // it's stuck in place — i.e. it landed and is about to be removed.
            return true
        }

        // Block raycast.
        val blockHit = world.raycast(
            RaycastContext(
                prevPos,
                curPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                entity
            )
        )
        if (blockHit.type == HitResult.Type.BLOCK) return true

        // Entity collision check — sweep the segment against nearby entity
        // bounding boxes. Skip armor stands (Hypixel uses them as
        // labels/decorations and they don't actually stop pearls) and
        // projectiles (other pearls/arrows don't intercept).
        val segBox = Box(prevPos, curPos).expand(1.0)
        val player = MinecraftClient.getInstance().player
        for (other in world.getOtherEntities(entity, segBox) { e ->
            e !is ArmorStandEntity &&
                    e !is EnderPearlEntity &&
                    e !== player &&  // own player can't intercept own pearl on Hypixel
                    e is LivingEntity &&
                    e.isAlive
        }) {
            val box = other.boundingBox.expand(other.targetingMargin.toDouble())
            val opt = box.raycast(prevPos, curPos)
            if (opt.isPresent) return true
        }
        return false
    }

    fun register() {
        ServerTickTracker.onTick {
            if (ticksSinceLastThrow < THROW_DEBOUNCE_TICKS) ticksSinceLastThrow++

            if (pearls.isEmpty()) {
                if (nextLabel != 1) nextLabel = 1
                return@onTick
            }

            tryBindPearls()

            val client = MinecraftClient.getInstance()
            val world = client.world
            val player = client.player

            val it = pearls.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                entry.ticksSinceThrow++

                when (entry.state) {
                    State.PENDING_BIND -> {
                        entry.remainingTicks--
                        if (entry.ticksSinceThrow >= BIND_TIMEOUT_TICKS) {
                            entry.state = State.PURE_PREDICTION
                        }
                        if (entry.remainingTicks <= 0) {
                            entry.remainingTicks = 0
                            entry.state = State.LANDED
                            entry.postLandHold = POST_LAND_HOLD_TICKS
                        }
                    }
                    State.TRACKING -> {
                        val ent = world?.getEntityById(entry.boundEntityId) as? EnderPearlEntity
                        val prevPos = entry.lastPos

                        if (ent != null && ent.isAlive && prevPos != null) {
                            // Per-tick collision raycast on the segment the
                            // entity actually traversed this tick.
                            if (didPearlLand(ent, prevPos)) {
                                entry.remainingTicks = 0
                                entry.state = State.LANDED
                                entry.postLandHold = POST_LAND_HOLD_TICKS
                            } else {
                                entry.lastPos = ent.pos
                                entry.lastVel = ent.velocity
                                entry.lastSeenAge = ent.age
                                entry.remainingTicks--
                                if (entry.remainingTicks < 0) entry.remainingTicks = 0
                            }
                        } else {
                            // Entity is gone. Was it killed (collision) or
                            // just unloaded out of view distance?
                            //
                            // Heuristic: if the last seen position was close
                            // to the player AND the entity is now NOT in the
                            // world, it almost certainly hit something.
                            // Otherwise it's an unload — fall back to
                            // continuing the simulation from the last known
                            // pos/vel.
                            val playerPos = player?.pos
                            val lastPos = entry.lastPos
                            val lastVel = entry.lastVel
                            val distToPlayer = if (playerPos != null && lastPos != null)
                                playerPos.distanceTo(lastPos) else Double.POSITIVE_INFINITY

                            if (distToPlayer < DEATH_VS_UNLOAD_THRESHOLD_BLOCKS) {
                                // Almost certainly a real collision.
                                entry.remainingTicks = 0
                                entry.state = State.LANDED
                                entry.postLandHold = POST_LAND_HOLD_TICKS
                            } else if (lastPos != null && lastVel != null) {
                                // Unload — switch to local simulation from the
                                // last known state.
                                val extrapolated = predictLandingTicks(lastPos, lastVel)
                                if (extrapolated != null) {
                                    entry.remainingTicks = extrapolated
                                }
                                entry.state = State.PURE_PREDICTION
                            } else {
                                // No last-known data — give up and decrement.
                                entry.state = State.PURE_PREDICTION
                            }
                        }
                    }
                    State.PURE_PREDICTION -> {
                        entry.remainingTicks--
                        if (entry.remainingTicks <= 0) {
                            entry.remainingTicks = 0
                            entry.state = State.LANDED
                            entry.postLandHold = POST_LAND_HOLD_TICKS
                        }
                    }
                    State.LANDED -> {
                        entry.postLandHold--
                        if (entry.postLandHold <= 0) it.remove()
                    }
                }
            }

            if (pearls.isEmpty()) nextLabel = 1
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerTickTracker.reset()
            pearls.clear()
            nextLabel = 1
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerTickTracker.reset()
            pearls.clear()
            nextLabel = 1
        }

        UseItemCallback.EVENT.register { player, _, hand ->
            if (!FamilyConfigManager.config.soloKuudra.pearlTimer) return@register ActionResult.PASS
            val client = MinecraftClient.getInstance()
            if (player != client.player) return@register ActionResult.PASS
            if (hand != Hand.MAIN_HAND) return@register ActionResult.PASS

            val stack = player.getStackInHand(hand)
            if (!isThrowablePearl(stack)) return@register ActionResult.PASS

            if (ticksSinceLastThrow < THROW_DEBOUNCE_TICKS) return@register ActionResult.PASS

            val landTicks = predictInitialLandingTicks()
            if (landTicks == null) {
                FamilyAddons.LOGGER.debug("PearlTimer: no impact predicted")
                return@register ActionResult.PASS
            }

            pearls.add(PearlEntry(id = nextLabel, remainingTicks = landTicks))
            nextLabel++
            ticksSinceLastThrow = 0

            ActionResult.PASS
        }

        HudRenderCallback.EVENT.register { context, _ ->
            if (!FamilyConfigManager.config.soloKuudra.pearlTimer) return@register
            if (pearls.isEmpty()) return@register

            val client = MinecraftClient.getInstance()
            val tr = client.textRenderer
            val scale = getScale()
            val fractional = ServerTickTracker.fractionalTicksSinceLastTick()
            val unit = FamilyConfigManager.config.soloKuudra.pearlDisplayUnit

            val lines = ArrayList<String>(pearls.size)
            var widestPlain = 0
            for (entry in pearls) {
                val displayTicks = if (entry.state == State.LANDED) 0.0
                else (entry.remainingTicks - fractional).coerceAtLeast(0.0)
                val line = formatPearlLine(entry.id, displayTicks, unit)
                lines.add(line)
                val w = tr.getWidth(line.replace(COLOR_CODE_REGEX, ""))
                if (w > widestPlain) widestPlain = w
            }

            val (x, y) = resolvePos(context.scaledWindowWidth, context.scaledWindowHeight, scale, widestPlain)
            val matrices = context.matrices
            matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale, scale)
            var ly = 0
            for (line in lines) {
                context.drawText(tr, Text.literal(line), 0, ly, -1, true)
                ly += 10
            }
            matrices.popMatrix()
        }
    }

    /**
     *  - Seconds: smooth fractional, e.g. "1.20s"
     *  - Ticks:   integer using ceil so the visible value drops at the same
     *             instant the underlying tick changes.
     */
    private fun formatPearlLine(id: Int, displayTicks: Double, unit: Int): String {
        return when (unit) {
            1 -> {
                val whole = ceil(displayTicks).toInt().coerceAtLeast(0)
                "§dPearl $id §f${whole}t"
            }
            else -> {
                val seconds = displayTicks * 0.05
                "§dPearl $id §f${"%.2f".format(seconds)}s"
            }
        }
    }
}