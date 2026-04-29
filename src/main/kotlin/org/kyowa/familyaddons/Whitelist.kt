package org.kyowa.familyaddons

import com.google.gson.JsonParser
import net.minecraft.client.MinecraftClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * UUID-based feature whitelist.
 *
 * Workflow:
 *  1. On mod init, [FamilyConfigManager.load] calls [check] with the player's
 *     session UUID (available immediately at boot from launcher auth).
 *  2. Hardcoded override list is consulted first — bypasses the worker entirely.
 *     This means the mod author always has access even if the worker is down.
 *  3. Otherwise: live HTTP query to https://whitelist.kyowa.uk/check?uuid=...
 *  4. **No caching.** Every launch hits the worker. Removes any "tamper a JSON
 *     file" attack surface and ensures revocations take effect immediately.
 *  5. Worker unreachable / network error → fail-CLOSED (allowed = false).
 *     This is intentional — without a working network check we can't trust
 *     the whitelist, so the safest default is to hide everything.
 *
 * Once [check] runs, [isAllowed] returns the cached result for cheap
 * per-frame lookups during render/tick. The result is in-memory only, never
 * written to disk.
 *
 * Features call [isAllowed] in their `register()` to decide whether to wire
 * up event listeners — non-whitelisted users don't even register handlers,
 * so the features are fully inert.
 */
object Whitelist {

    private const val ENDPOINT = "https://whitelist.kyowa.uk/check"

    /** UUIDs that always bypass the worker check. Trimmed (32-char no-dash) form. */
    private val HARDCODED_OVERRIDES: Set<String> = setOf(
        "305bcf8ca93d4d529e8cb925e8d25682",  // KyoWaa (mod author)
    )

    /** In-memory state only. Set by [check], read by [isAllowed]. */
    @Volatile private var allowed: Boolean = false
    @Volatile private var checked: Boolean = false
    @Volatile private var lastReason: String = "(not checked yet)"

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    fun isAllowed(): Boolean = allowed
    fun isChecked(): Boolean = checked

    /**
     * Resolves whitelist status for the given UUID. Call once during mod init
     * with the session UUID, and again on first world JOIN as defense-in-depth.
     */
    fun check(uuid: UUID?) {
        if (uuid == null) {
            // Can't check without a UUID — this should be rare since session UUID
            // is available at boot. Fail-closed.
            allowed = false
            checked = true
            lastReason = "no UUID provided"
            log("No UUID — failing CLOSED")
            return
        }

        val trimmed = uuid.toString().replace("-", "").lowercase()

        // 1. Hardcoded override.
        if (trimmed in HARDCODED_OVERRIDES) {
            allowed = true
            checked = true
            lastReason = "hardcoded override"
            log("UUID $trimmed allowed via hardcoded override")
            return
        }

        // 2. Live worker query. No cache lookup.
        val result = try {
            queryWorker(trimmed)
        } catch (e: Exception) {
            log("Worker query failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        if (result != null) {
            allowed = result
            checked = true
            lastReason = "worker said $result"
            log("UUID $trimmed allowed=$result from worker")
            return
        }

        // 3. Worker unreachable — fail-CLOSED.
        allowed = false
        checked = true
        lastReason = "worker unreachable, failing closed"
        log("Worker unreachable — failing CLOSED for UUID $trimmed")
    }

    /**
     * Calls the Cloudflare Worker.
     *
     * Worker contract:
     *   GET https://whitelist.kyowa.uk/check?uuid=<trimmed>
     *   200 OK + JSON body {"allowed": true}  → whitelisted
     *   200 OK + JSON body {"allowed": false} → not whitelisted
     *   any other status → throw, treated as failure
     */
    private fun queryWorker(trimmed: String): Boolean {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$ENDPOINT?uuid=$trimmed"))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "FamilyAddons/whitelist")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            error("HTTP ${resp.statusCode()}")
        }
        val body = JsonParser.parseString(resp.body()).asJsonObject
        return body.get("allowed")?.asBoolean ?: false
    }

    private fun log(msg: String) {
        println("[FamilyAddons/Whitelist] $msg")
    }

    /** Returns a debug summary for /fawl. */
    fun debugDump(): String {
        val mc = MinecraftClient.getInstance()
        val uuid = mc.session?.uuidOrNull?.toString()?.replace("-", "")?.lowercase() ?: "(unknown)"
        return buildString {
            append("§6[FA Whitelist]§7\n")
            append("§7UUID: §f$uuid\n")
            append("§7Allowed: ").append(if (allowed) "§atrue" else "§cfalse").append("\n")
            append("§7Checked: ").append(if (checked) "§atrue" else "§cfalse").append("\n")
            append("§7Hardcoded override: ").append(if (uuid in HARDCODED_OVERRIDES) "§atrue" else "§cfalse").append("\n")
            append("§7Last reason: §f").append(lastReason).append("\n")
        }
    }
}