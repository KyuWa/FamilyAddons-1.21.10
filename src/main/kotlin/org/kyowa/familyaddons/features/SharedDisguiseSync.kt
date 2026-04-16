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

    data class SyncedDisguise(val mobId: String, val baby: Boolean)

    // username (lowercase) → disguise
    @Volatile
    var remoteDisguises: Map<String, SyncedDisguise> = emptyMap()
        private set

    private val http = HttpClient.newHttpClient()
    private var tickCounter = 0

    // ── Push your own disguise to Cloudflare ─────────────────────────
    fun pushMyDisguise() {
        val cfg = FamilyConfigManager.config.playerDisguise
        if (!cfg.enabled) {
            deleteMyDisguise()
            return
        }
        val username = MinecraftClient.getInstance().session.username ?: return
        CompletableFuture.runAsync {
            try {
                val body = """{"username":"$username","mobId":"${cfg.mobId}","baby":${cfg.baby}}"""
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
        val username = MinecraftClient.getInstance().session.username ?: return
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
                    result[name.lowercase()] = SyncedDisguise(mobId, baby)
                }
                remoteDisguises = result
                FamilyAddons.LOGGER.info("SharedDisguiseSync: fetched ${result.size} disguises: ${result.keys}")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("SharedDisguiseSync: fetch failed: ${e.message}")
            }
        }
    }

    // ── Register polling (every 30s) and join/leave hooks ────────────
    fun register() {
        // Poll every 30 seconds
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (tickCounter++ % 600 != 0) return@register
            fetchAll()
        }

        // Fetch immediately on world join, push your own disguise
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            fetchAll()
            val cfg = FamilyConfigManager.config.playerDisguise
            if (cfg.enabled) pushMyDisguise()
        }

        // Clean up on disconnect
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            remoteDisguises = emptyMap()
        }
    }

    // ── Called by PlayerDisguiseMixin for non-self players ───────────
    fun getDisguise(username: String): SyncedDisguise? =
        remoteDisguises[username.lowercase()]
}