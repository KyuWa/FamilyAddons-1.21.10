package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.Camera
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.VertexRendering
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.world.chunk.WorldChunk
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.opengl.GL11
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object WorldScanner {

    data class WaypointData(
        val pos: BlockPos,
        val category: Int,  // 0=Crystal, 1=Mob, 2=Grotto, 3=Dragon, 4=Worm
        val r: Float,
        val g: Float,
        val b: Float
    )

    private val waypoints = ConcurrentHashMap<String, WaypointData>()
    private val scannedChunks = ConcurrentHashMap.newKeySet<Long>()
    private val lavaBlocks = ConcurrentHashMap.newKeySet<Long>()
    private val waterBlocks = ConcurrentHashMap.newKeySet<Long>()

    // ── Quarter bounds ────────────────────────────────────────────────
    private enum class Quarter {
        NUCLEUS, JUNGLE, PRECURSOR, GOBLIN, MITHRIL, MAGMA, ANY;
        fun test(x: Int, y: Int, z: Int): Boolean = when (this) {
            NUCLEUS   -> x in 449..576 && z in 449..576
            JUNGLE    -> x <= 576 && z <= 576
            PRECURSOR -> x > 448 && z > 448
            GOBLIN    -> x <= 576 && z > 448
            MITHRIL   -> x > 448 && z <= 576
            MAGMA     -> y < 80
            ANY       -> true
        }
    }

    // ── Structure definitions ─────────────────────────────────────────
    private enum class Structure(
        val displayName: String,
        val blocks: List<Block?>,
        val r: Float, val g: Float, val b: Float,
        val quarter: Quarter,
        val offsetX: Int = 0, val offsetY: Int = 0, val offsetZ: Int = 0,
        val category: Int
    ) {
        KING("King",
            listOf(Blocks.RED_WOOL, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS),
            1f, 0.67f, 0f, Quarter.GOBLIN, 1, -1, 2, 0),
        QUEEN("Queen",
            listOf(Blocks.STONE, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.ACACIA_WOOD, Blocks.CAULDRON),
            1f, 0.67f, 0f, Quarter.ANY, 0, 5, 0, 0),
        DIVAN("Divan",
            listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS),
            0f, 1f, 0f, Quarter.MITHRIL, 0, 5, 0, 0),
        CITY("City",
            listOf(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE,
                Blocks.COBBLESTONE_STAIRS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS),
            0f, 1f, 1f, Quarter.PRECURSOR, 24, 0, -17, 0),
        TEMPLE("Temple",
            listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.BEDROCK, Blocks.STONE, Blocks.CLAY,
                Blocks.CLAY, Blocks.CLAY, Blocks.OAK_LEAVES, Blocks.OAK_LEAVES, Blocks.LIME_TERRACOTTA,
                Blocks.LIME_TERRACOTTA, Blocks.GREEN_TERRACOTTA),
            0.67f, 0f, 1f, Quarter.ANY, -45, 47, -18, 0),
        BAL("Bal",
            listOf(Blocks.LAVA, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER,
                Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER),
            1f, 0.67f, 0f, Quarter.MAGMA, 0, 1, 0, 0),
        CORLEONE_DOCK("Corleone Dock",
            listOf(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.FIRE, Blocks.STONE_BRICKS),
            0f, 1f, 0f, Quarter.MITHRIL, 23, 11, 17, 1),
        CORLEONE_HOLE("Corleone Hole",
            listOf(Blocks.SMOOTH_STONE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_GRANITE),
            0f, 1f, 0f, Quarter.MITHRIL, -18, -1, 29, 1),
        KEY_GUARDIAN_SPIRAL("Key Guardian Spiral",
            listOf(Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE),
            0.67f, 0f, 1f, Quarter.JUNGLE, 0, 0, 0, 1),
        KEY_GUARDIAN_TOWER("Key Guardian Tower",
            listOf(Blocks.STONE, Blocks.POLISHED_GRANITE, Blocks.JUNGLE_SLAB),
            0.67f, 0f, 1f, Quarter.JUNGLE, 0, 0, 0, 1),
        XALX("Xalx",
            listOf(Blocks.STONE, Blocks.COAL_BLOCK, Blocks.FIRE, Blocks.NETHER_QUARTZ_ORE,
                Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR),
            0f, 1f, 0f, Quarter.GOBLIN, -2, 1, -2, 1),
        PETE("Pete",
            listOf(Blocks.NETHERRACK, Blocks.FIRE, Blocks.IRON_BARS,
                Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR),
            1f, 0.67f, 0f, Quarter.GOBLIN, 0, 0, 0, 1),
        ODAWA("Odawa",
            listOf(Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG),
            0f, 1f, 0f, Quarter.JUNGLE, 0, 0, 0, 1),
        GOLDEN_DRAGON("Golden Dragon",
            listOf(Blocks.STONE, Blocks.RED_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.RED_TERRACOTTA,
                Blocks.PLAYER_HEAD, Blocks.RED_WOOL),
            1f, 1f, 1f, Quarter.ANY, 0, -3, 5, 3)
    }

    // ── Register ──────────────────────────────────────────────────────
    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> clearAll() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clearAll() }

        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (!FamilyConfigManager.config.worldScanner.enabled) return@register
            if (!isInCrystalHollows()) return@register
            val key = chunkKey(chunk.pos.x, chunk.pos.z)
            if (!scannedChunks.add(key)) return@register
            Thread {
                try { scanChunk(chunk) } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────
    private fun scanChunk(chunk: WorldChunk) {
        val cfg = FamilyConfigManager.config.worldScanner
        val mutablePos = BlockPos.Mutable()

        for (lx in 0..15) {
            for (lz in 0..15) {
                val wx = chunk.pos.x * 16 + lx
                val wz = chunk.pos.z * 16 + lz

                for (y in 0..170) {
                    val state = getBlockState(chunk, lx, y, lz)
                    if (state.isAir) continue
                    val block = state.block

                    when (block) {
                        Blocks.RED_WOOL -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("King") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KING.blocks))
                                    addWaypoint(Structure.KING, wx, y, wz)
                        }
                        Blocks.STONE -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Queen") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.QUEEN.blocks))
                                    addWaypoint(Structure.QUEEN, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Key Guardian Tower") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KEY_GUARDIAN_TOWER.blocks))
                                    addWaypoint(Structure.KEY_GUARDIAN_TOWER, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Xalx") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.XALX.blocks))
                                    addWaypoint(Structure.XALX, wx, y, wz)
                            if (cfg.scanDragonNest && !waypoints.containsKey("Golden Dragon") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.GOLDEN_DRAGON.blocks))
                                    addWaypoint(Structure.GOLDEN_DRAGON, wx, y, wz)
                        }
                        Blocks.QUARTZ_PILLAR -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Divan") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.DIVAN.blocks))
                                    addWaypoint(Structure.DIVAN, wx, y, wz)
                        }
                        Blocks.STONE_BRICKS -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("City") && Quarter.PRECURSOR.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CITY.blocks))
                                    addWaypoint(Structure.CITY, wx, y, wz)
                            if (cfg.scanMobSpots && !waypoints.containsKey("Corleone Dock") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CORLEONE_DOCK.blocks))
                                    addWaypoint(Structure.CORLEONE_DOCK, wx, y, wz)
                        }
                        Blocks.BEDROCK -> {
                            if (cfg.scanCrystals && !waypoints.containsKey("Temple") && Quarter.ANY.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.TEMPLE.blocks))
                                    addWaypoint(Structure.TEMPLE, wx, y, wz)
                        }
                        Blocks.LAVA -> {
                            if (cfg.lavaEsp && getBlockState(chunk, lx, y + 1, lz).isAir)
                                lavaBlocks.add(BlockPos(wx, y, wz).asLong())
                            if (cfg.scanCrystals && !waypoints.containsKey("Bal") && Quarter.MAGMA.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.BAL.blocks))
                                    addWaypoint(Structure.BAL, wx, y, wz)
                            if (cfg.scanWormFishing && y > 63 && !waypoints.containsKey("Worm Fishing")
                                && ((wx >= 564 && wz >= 513) || (wx >= 513 && wz >= 564))) {
                                if (getBlockState(chunk, lx, y + 1, lz).isAir) {
                                    mutablePos.set(wx, y, wz)
                                    addWaypointDirect("Worm Fishing", mutablePos, 1f, 0.67f, 0f, 4)
                                }
                            }
                        }
                        Blocks.WATER -> {
                            if (cfg.waterEsp && getBlockState(chunk, lx, y + 1, lz).isAir)
                                waterBlocks.add(BlockPos(wx, y, wz).asLong())
                        }
                        Blocks.SMOOTH_STONE_SLAB -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Corleone Hole") && Quarter.MITHRIL.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.CORLEONE_HOLE.blocks))
                                    addWaypoint(Structure.CORLEONE_HOLE, wx, y, wz)
                        }
                        Blocks.JUNGLE_STAIRS -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Key Guardian Spiral") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.KEY_GUARDIAN_SPIRAL.blocks))
                                    addWaypoint(Structure.KEY_GUARDIAN_SPIRAL, wx, y, wz)
                        }
                        Blocks.NETHERRACK -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Pete") && Quarter.GOBLIN.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.PETE.blocks))
                                    addWaypoint(Structure.PETE, wx, y, wz)
                        }
                        Blocks.JUNGLE_LOG -> {
                            if (cfg.scanMobSpots && !waypoints.containsKey("Odawa") && Quarter.JUNGLE.test(wx, y, wz))
                                if (checkSequence(chunk, lx, y, lz, Structure.ODAWA.blocks))
                                    addWaypoint(Structure.ODAWA, wx, y, wz)
                        }
                        Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE -> {
                            if (cfg.scanFairyGrottos && !waypoints.containsKey("Fairy Grotto") && !Quarter.NUCLEUS.test(wx, y, wz)) {
                                mutablePos.set(wx, y, wz)
                                addWaypointDirect("Fairy Grotto", mutablePos, 1f, 0.41f, 1f, 2)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────
    fun hasWaypoints(): Boolean = waypoints.isNotEmpty() || lavaBlocks.isNotEmpty() || waterBlocks.isNotEmpty()

    fun onWorldRender(matrices: MatrixStack, camera: Camera) {
        val cfg = FamilyConfigManager.config.worldScanner
        if (!cfg.enabled) return
        if (!isInCrystalHollows()) return
        if (!hasWaypoints()) return

        val client = MinecraftClient.getInstance()
        val cam = camera.pos

        val visible = waypoints.entries.filter { (_, data) ->
            when (data.category) {
                0 -> cfg.scanCrystals
                1 -> cfg.scanMobSpots
                2 -> cfg.scanFairyGrottos
                3 -> cfg.scanDragonNest
                4 -> cfg.scanWormFishing
                else -> false
            }
        }

        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        // Pass 1: with depth, full alpha
        val immediate = client.bufferBuilders?.entityVertexConsumers ?: run { matrices.pop(); return }
        for ((_, data) in visible) {
            val box = Box(data.pos.x.toDouble(), data.pos.y.toDouble(), data.pos.z.toDouble(),
                data.pos.x + 1.0, data.pos.y + 1.0, data.pos.z + 1.0)
            VertexRendering.drawBox(matrices.peek(), immediate.getBuffer(RenderLayer.getLines()), box, data.r, data.g, data.b, 1.0f)
        }
        immediate.draw(RenderLayer.getLines())

        // Pass 2: through walls, full alpha
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        val immediate2 = client.bufferBuilders?.entityVertexConsumers ?: run { GL11.glEnable(GL11.GL_DEPTH_TEST); matrices.pop(); return }
        for ((_, data) in visible) {
            val box = Box(data.pos.x.toDouble(), data.pos.y.toDouble(), data.pos.z.toDouble(),
                data.pos.x + 1.0, data.pos.y + 1.0, data.pos.z + 1.0)
            VertexRendering.drawBox(matrices.peek(), immediate2.getBuffer(RenderLayer.getLines()), box, data.r, data.g, data.b, 1.0f)
        }

        // Lava ESP
        if (cfg.lavaEsp) {
            val rangeSq = cfg.espRange * cfg.espRange
            for (packed in lavaBlocks) {
                val pos = BlockPos.fromLong(packed)
                val dx = pos.x - cam.x; val dy = pos.y - cam.y; val dz = pos.z - cam.z
                if (dx*dx + dy*dy + dz*dz <= rangeSq) {
                    val box = Box(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
                    VertexRendering.drawBox(matrices.peek(), immediate2.getBuffer(RenderLayer.getLines()), box, 1f, 0.67f, 0f, 1.0f)
                }
            }
        }

        // Water ESP
        if (cfg.waterEsp) {
            val rangeSq = cfg.espRange * cfg.espRange
            for (packed in waterBlocks) {
                val pos = BlockPos.fromLong(packed)
                val dx = pos.x - cam.x; val dy = pos.y - cam.y; val dz = pos.z - cam.z
                if (dx*dx + dy*dy + dz*dz <= rangeSq) {
                    val box = Box(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
                    VertexRendering.drawBox(matrices.peek(), immediate2.getBuffer(RenderLayer.getLines()), box, 0f, 0.67f, 1f, 1.0f)
                }
            }
        }

        immediate2.draw(RenderLayer.getLines())
        GL11.glEnable(GL11.GL_DEPTH_TEST)

        matrices.pop()

        // Labels
        if (cfg.renderText) {
            val labelBuffer = client.bufferBuilders?.entityVertexConsumers ?: return
            for ((name, data) in visible) {
                val wx = data.pos.x + 0.5; val wy = data.pos.y + 2.2; val wz = data.pos.z + 0.5
                val dx = wx - cam.x; val dy = wy - cam.y; val dz = wz - cam.z
                val dist = sqrt(dx*dx + dy*dy + dz*dz)
                renderLabel(matrices, labelBuffer, cam.x, cam.y, cam.z, wx, wy, wz,
                    "§f$name §7(${dist.toInt()}m)", dist)
            }
            labelBuffer.draw()
        }
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

        // Scale grows with distance, min=1.0 max=5.0, then multiplied by base 0.025
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

    // ── Helpers ───────────────────────────────────────────────────────
    private fun addWaypoint(structure: Structure, x: Int, y: Int, z: Int) {
        if (waypoints.containsKey(structure.displayName)) return
        val targetPos = BlockPos(x + structure.offsetX, y + structure.offsetY, z + structure.offsetZ)
        waypoints[structure.displayName] = WaypointData(targetPos, structure.category, structure.r, structure.g, structure.b)
        if (FamilyConfigManager.config.worldScanner.sendCoordsInChat) {
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().player?.sendMessage(
                    net.minecraft.text.Text.literal("§6[FA] §a${structure.displayName} §7found at §b${targetPos.x}, ${targetPos.y}, ${targetPos.z}"),
                    false
                )
            }
        }
    }

    private fun addWaypointDirect(name: String, pos: BlockPos, r: Float, g: Float, b: Float, category: Int) {
        if (waypoints.containsKey(name)) return
        waypoints[name] = WaypointData(pos.toImmutable(), category, r, g, b)
        if (FamilyConfigManager.config.worldScanner.sendCoordsInChat) {
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().player?.sendMessage(
                    net.minecraft.text.Text.literal("§6[FA] §a$name §7found at §b${pos.x}, ${pos.y}, ${pos.z}"),
                    false
                )
            }
        }
    }

    private fun checkSequence(chunk: WorldChunk, lx: Int, y: Int, lz: Int, sequence: List<Block?>): Boolean {
        val mutable = BlockPos.Mutable()
        for (i in sequence.indices) {
            val expected = sequence[i] ?: continue
            val py = y + i
            if (py > 255) return false
            mutable.set(lx, py, lz)
            if (chunk.getBlockState(mutable).block != expected) return false
        }
        return true
    }

    private fun getBlockState(chunk: WorldChunk, lx: Int, y: Int, lz: Int): BlockState {
        if (y < 0 || y > 255) return Blocks.AIR.defaultState
        val sectionIndex = (y shr 4) - chunk.bottomSectionCoord
        val sections = chunk.sectionArray
        if (sectionIndex < 0 || sectionIndex >= sections.size) return Blocks.AIR.defaultState
        val section = sections[sectionIndex] ?: return Blocks.AIR.defaultState
        if (section.isEmpty) return Blocks.AIR.defaultState
        return section.getBlockState(lx, y and 15, lz)
    }

    private fun isInCrystalHollows(): Boolean {
        return try {
            val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return false
            for (entry in tabList) {
                val name = entry.displayName?.string?.replace(COLOR_CODE_REGEX, "")?.trim() ?: continue
                if (name.startsWith("Area:")) return name.removePrefix("Area:").trim() == "Crystal Hollows"
            }
            false
        } catch (_: Exception) { false }
    }

    private fun clearAll() {
        waypoints.clear()
        scannedChunks.clear()
        lavaBlocks.clear()
        waterBlocks.clear()
    }

    private fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
}