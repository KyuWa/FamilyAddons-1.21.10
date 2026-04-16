package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

object SharedDisguiseSync {

    // ── Replace with your actual Worker URL ──────────────────────────
    private const val WORKER_URL = "https://little-frog-551e.220395610.workers.dev"

    // ── Replace with your FA_SECRET value set in the Worker env ──────
    private const val SECRET = "kyowa-fa-secret-2025"

    data class SyncedDisguise(val mobId: String, val baby: Boolean, val sheared: Boolean = false)

    // username (lowercase) → disguise
    @Volatile
    var remoteDisguises: Map<String, SyncedDisguise> = emptyMap()
        private set

    private val http = HttpClient.newHttpClient()
    private var tickCounter = 0

    // Track last-pushed values to detect changes and push instantly
    private var lastPushedEnabled: Boolean? = null
    private var lastPushedMobId: String? = null
    private var lastPushedBaby: Boolean? = null
    private var lastPushedSheared: Boolean? = null

    // ── Push your own disguise to Cloudflare ─────────────────────────
    fun pushMyDisguise() {
        val cfg = FamilyConfigManager.config.playerDisguise
        if (!cfg.enabled) {
            deleteMyDisguise()
            return
        }
        val client = MinecraftClient.getInstance()
        val username = client.player?.name?.string
            ?: client.session?.username
            ?: return
        CompletableFuture.runAsync {
            try {
                val body = """{"username":"$username","mobId":"${cfg.mobId}","baby":${cfg.baby},"sheared":${cfg.sheared}}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise"))
                    .header("Content-Type", "application/json")
                    .header("X-FA-Secret", SECRET)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.ofString())
                FamilyAddons.LOGGER.info("SharedDisguiseSync: pushed disguise for $username → ${cfg.mobId}")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: push failed: ${e.message}")
            }
        }
    }

    // ── Remove your disguise from Cloudflare when disabled ───────────
    fun deleteMyDisguise() {
        val client = MinecraftClient.getInstance()
        val username = client.player?.name?.string
            ?: client.session?.username
            ?: return
        CompletableFuture.runAsync {
            try {
                val body = """{"username":"$username"}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise"))
                    .header("Content-Type", "application/json")
                    .header("X-FA-Secret", SECRET)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: delete failed: ${e.message}")
            }
        }
    }

    // ── Fetch all disguises from Cloudflare ──────────────────────────
    // Public so the config button can call it directly
    fun fetchAllNow() { fetchAll() }

    private fun fetchAll() {
        CompletableFuture.runAsync {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$WORKER_URL/disguise/all"))
                    .GET()
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                val json = JsonParser.parseString(resp.body()).asJsonObject
                val result = mutableMapOf<String, SyncedDisguise>()
                for ((name, entry) in json.entrySet()) {
                    val obj = entry.asJsonObject
                    val mobId = obj.get("mobId")?.asString ?: continue
                    val baby = obj.get("baby")?.asBoolean ?: false
                    val sheared = obj.get("sheared")?.asBoolean ?: false
                    result[name.lowercase()] = SyncedDisguise(mobId, baby, sheared)
                }
                remoteDisguises = result
                FamilyAddons.LOGGER.info("SharedDisguiseSync: fetched ${result.size} disguises: ${result.keys}")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: fetch failed: ${e.message}")
            }
        }
    }

    // ── Register polling, realtime change detection, and join/leave hooks ──
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val t = tickCounter++

            // Check for disguise config changes every 20 ticks (~1s) and push instantly
            if (t % 20 == 0) {
                val cfg = FamilyConfigManager.config.playerDisguise
                val changed = cfg.enabled != lastPushedEnabled ||
                        cfg.mobId != lastPushedMobId ||
                        cfg.baby != lastPushedBaby
                if (changed) {
                    lastPushedEnabled = cfg.enabled
                    lastPushedMobId = cfg.mobId
                    lastPushedBaby = cfg.baby
                    lastPushedSheared = cfg.sheared
                    if (cfg.enabled) pushMyDisguise() else deleteMyDisguise()
                }
            }

            // Poll all disguises every 30s
            if (t % 600 == 0) fetchAll()
        }

        // Fetch immediately on world join
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            fetchAll()
            // Reset last-pushed so it re-pushes on join
            lastPushedEnabled = null
            lastPushedMobId = null
            lastPushedBaby = null
            lastPushedSheared = null
        }

        // Clean up on disconnect
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            remoteDisguises = emptyMap()
            lastPushedEnabled = null
            lastPushedMobId = null
            lastPushedBaby = null
            lastPushedSheared = null
        }
    }

    // ── Called by PlayerDisguiseMixin for non-self players ───────────
    fun getDisguise(username: String): SyncedDisguise? {
        if (!FamilyConfigManager.config.playerDisguise.showFriendsDisguises) return null
        return remoteDisguises[username.lowercase()]
    }
}