package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager

object BestiaryZoneHighlight {

    val ZONES = listOf(
        "None",
        "Island",
        "Hub",
        "The Farming Lands",
        "The Garden",
        "Spider's Den",
        "The End",
        "Crimson Isle",
        "Deep Caverns",
        "Dwarven Mines",
        "Crystal Hollows",
        "The Park",
        "Galatea",
        "Spooky Festival",
        "The Catacombs",
        "Fishing",
        "Mythological Creatures",
        "Jerry",
        "Kuudra"
    )

    // Confirmed from actual NEU bestiary.json top-level keys:
    // [dynamic, hub, farming_1, combat_1, combat_3, crimson_isle, mining_2, mining_3,
    //  crystal_hollows, foraging_1, foraging_2, spooky_festival, mythological_creatures,
    //  jerry, kuudra, fishing, catacombs, garden]
    //
    // Mapping:
    //   dynamic          = Private Island
    //   hub              = Hub
    //   farming_1        = The Farming Lands
    //   garden           = The Garden
    //   combat_1         = Spider's Den
    //   combat_3         = The End
    //   crimson_isle     = Crimson Isle
    //   mining_2         = Deep Caverns
    //   mining_3         = Dwarven Mines
    //   crystal_hollows  = Crystal Hollows
    //   foraging_1       = The Park
    //   foraging_2       = Galatea  (second foraging zone)
    //   spooky_festival  = Spooky Festival
    //   catacombs        = The Catacombs
    //   fishing          = Fishing
    //   mythological_creatures = Mythological Creatures
    //   jerry            = Jerry
    //   kuudra           = Kuudra
    private val ZONE_TO_NEU_KEY = mapOf(
        "Island"                to "dynamic",
        "Hub"                   to "hub",
        "The Farming Lands"     to "farming_1",
        "The Garden"            to "garden",
        "Spider's Den"          to "combat_1",
        "The End"               to "combat_3",
        "Crimson Isle"          to "crimson_isle",
        "Deep Caverns"          to "mining_2",
        "Dwarven Mines"         to "mining_3",
        "Crystal Hollows"       to "crystal_hollows",
        "The Park"              to "foraging_1",
        "Galatea"               to "foraging_2",
        "Spooky Festival"       to "spooky_festival",
        "The Catacombs"         to "catacombs",
        "Fishing"               to "fishing",
        "Mythological Creatures" to "mythological_creatures",
        "Jerry"                 to "jerry",
        "Kuudra"                to "kuudra"
    )

    // Full list of mob names for the selected zone (before max filtering)
    // Set by refresh() every 30s. checkMaxFromTablist() filters this down to activeMobNames.
    @Volatile var allZoneMobNames: Set<String> = emptySet()
        private set

    // Filtered list — allZoneMobNames minus any that show MAX in the tablist
    @Volatile var activeMobNames: Set<String> = emptySet()
        private set

    private val httpClient = HttpClient.newHttpClient()
    private var tickCounter = 0

    private data class MobEntry(val displayName: String, val mobIds: List<String>, val maxKills: Long)
    private var repoData: Map<String, List<MobEntry>> = emptyMap()
    private var repoLoaded = false

    // Mobs whose in-game entity name differs from the bestiary display name.
    // Applied consistently everywhere so activeMobNames and maxed sets always use the same names.
    // Key = lowercased display name for matching, value = display name to store (original casing for display)
    private val NAME_REMAPS = mapOf(
        "sneaky creeper" to "Creeper"
    )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val cfg = FamilyConfigManager.config.bestiary
            if (!cfg.zoneHighlightEnabled) {
                if (activeMobNames.isNotEmpty()) activeMobNames = emptySet()
                return@register
            }
            if (cfg.bestiaryZone == 0) {
                if (activeMobNames.isNotEmpty()) activeMobNames = emptySet()
                return@register
            }
            // Refresh every 30s (600 ticks)
            if (tickCounter++ % 600 != 0) return@register
            refresh()
        }
    }

    fun refresh() {
        CompletableFuture.runAsync {
            try {
                if (!repoLoaded) loadRepo()

                val cfg = FamilyConfigManager.config.bestiary
                val zoneIndex = cfg.bestiaryZone
                if (zoneIndex <= 0 || zoneIndex >= ZONES.size) { activeMobNames = emptySet(); return@runAsync }
                val zoneName = ZONES[zoneIndex]
                val neuKey = ZONE_TO_NEU_KEY[zoneName] ?: run {
                    FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: no key mapped for '$zoneName'")
                    activeMobNames = emptySet()
                    return@runAsync
                }

                val zoneMobs = repoData[neuKey]
                if (zoneMobs.isNullOrEmpty()) {
                    FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: zone '$neuKey' has no mobs in repo")
                    activeMobNames = emptySet()
                    return@runAsync
                }

                // NAME_REMAPS is defined at object level — see below

                // Build full zone mob name set (with name remaps applied)
                val fullSet = mutableSetOf<String>()
                for (mob in zoneMobs) {
                    val cleanName = mob.displayName
                        .replace(Regex("§[0-9a-fk-or]"), "")
                        .trim()
                    fullSet.add(NAME_REMAPS[cleanName.lowercase()] ?: cleanName)
                }
                allZoneMobNames = fullSet
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: $zoneName zone loaded — ${fullSet.size} mobs: $fullSet")

                // Fast path: apply tablist MAX filter immediately
                checkMaxFromTablist()

                // Thorough path: also cross-check against Hypixel API kill counts
                // This catches mobs that are maxed but not currently in the tablist Bestiary section
                val apiKey = KeyFetcher.getApiKey()
                if (!apiKey.isNullOrBlank()) {
                    val player = MinecraftClient.getInstance().player
                    if (player != null) {
                        val uuid = player.gameProfile.id.toString().replace("-", "")
                        val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
                        if (data?.get("success")?.asBoolean == true) {
                            val profiles = data.getAsJsonArray("profiles")
                            val profile = profiles?.map { it.asJsonObject }
                                ?.firstOrNull { it.get("selected")?.asBoolean == true }
                                ?: profiles?.lastOrNull()?.asJsonObject
                            val member = profile?.getAsJsonObject("members")?.getAsJsonObject(uuid)
                            val killsObj = member?.getAsJsonObject("bestiary")?.getAsJsonObject("kills")
                            if (killsObj != null) {
                                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API killsObj keys sample: ${killsObj.keySet().take(5)}")
                                val apiMaxed = mutableSetOf<String>()
                                for (mob in zoneMobs) {
                                    val cleanName = mob.displayName
                                        .replace(Regex("§[0-9a-fk-or]"), "")
                                        .trim()
                                    val mappedName = NAME_REMAPS[cleanName.lowercase()] ?: cleanName
                                    val total = mob.mobIds.sumOf { id -> killsObj.get(id)?.asLong ?: 0L }
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: mob '$cleanName' ids=${mob.mobIds} total=$total max=${mob.maxKills}")
                                    if (total >= mob.maxKills) apiMaxed.add(mappedName)
                                }
                                // Merge API maxed with any tablist-detected maxed mobs
                                val combined = allZoneMobNames.minus(apiMaxed)
                                if (combined != activeMobNames) {
                                    activeMobNames = combined
                                    MinecraftClient.getInstance().execute { EntityHighlight.rescan() }
                                    FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API MAX check → ${combined.size} active: $combined")
                                    if (apiMaxed.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: API maxed: $apiMaxed")
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("BestiaryZoneHighlight error: ${e.message}")
            }
        }
    }

    // ── Called by BestiaryTracker every ~3s after its tablist poll ──────
    // Filters allZoneMobNames by removing any that show MAX in the tablist.
    fun checkMaxFromTablist() {
        if (!FamilyConfigManager.config.bestiary.zoneHighlightEnabled) return
        if (allZoneMobNames.isEmpty()) return

        val maxed = readMaxedMobsFromTablist()
        val filtered = allZoneMobNames.minus(maxed)
        if (filtered != activeMobNames) {
            activeMobNames = filtered
            MinecraftClient.getInstance().execute { EntityHighlight.rescan() }
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: MAX check → ${filtered.size} active: $filtered")
        }
    }

    // ── Read maxed mobs from tablist ──────────────────────────────────
    // Scans ALL tablist entries for the pattern " Mob Name N: MAX"
    // Does NOT rely on section order — entries are scattered in the tablist.
    // Pattern: leading space + "Name Tier: MAX" where Tier is a number.
    private fun readMaxedMobsFromTablist(): Set<String> {
        val tabList = MinecraftClient.getInstance().networkHandler?.playerList
            ?: return emptySet()

        val maxed = mutableSetOf<String>()
        // Matches: " Lapis Zombie 9: MAX" or " Sneaky Creeper 10: MAX"
        // Group 1 = mob name (may have spaces), Group 2 = tier number
        val pattern = Regex("""^\s+(.+?)\s+(\d+):\s+MAX\s*$""", RegexOption.IGNORE_CASE)

        for (entry in tabList) {
            val raw = entry.displayName?.string ?: continue
            val clean = raw.replace(COLOR_CODE_REGEX, "")
            val match = pattern.matchEntire(clean) ?: continue
            val mobNameRaw = match.groupValues[1].trim()
            if (mobNameRaw.isNotBlank()) {
                // Apply same name remaps so "Sneaky Creeper" → "Creeper" matches activeMobNames
                maxed.add(NAME_REMAPS[mobNameRaw.lowercase()] ?: mobNameRaw)
            }
        }
        return maxed
    }

    private fun loadRepo() {
        try {
            val json = getRaw("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json")
                ?: return

            val root = JsonParser.parseString(json).asJsonObject
            val result = mutableMapOf<String, List<MobEntry>>()

            // brackets: { "1": [100, 250, 500, ...], "2": [...] }
            val brackets = mutableMapOf<Int, List<Long>>()
            root.getAsJsonObject("brackets")?.let { bracketsObj ->
                for ((k, v) in bracketsObj.entrySet()) {
                    val num = k.toIntOrNull() ?: continue
                    brackets[num] = v.asJsonArray.map { it.asLong }
                }
            }

            for ((zoneKey, zoneVal) in root.entrySet()) {
                if (zoneKey == "brackets") continue
                val entries = mutableListOf<MobEntry>()

                fun parseMobsArray(obj: com.google.gson.JsonObject) {
                    obj.getAsJsonArray("mobs")?.forEach { mobEl ->
                        val mobObj = mobEl.asJsonObject
                        val name = mobObj.get("name")?.asString ?: return@forEach
                        val mobIds = mobObj.getAsJsonArray("mobs")
                            ?.map { it.asString }
                            ?: listOf(name.lowercase().replace(" ", "_"))
                        val bracket = mobObj.get("bracket")?.asInt ?: 1
                        val tierList = brackets[bracket] ?: listOf(250L)
                        entries.add(MobEntry(name, mobIds, tierList.last()))
                    }
                }

                try {
                    val zoneObj = zoneVal.asJsonObject
                    if (zoneObj.has("mobs")) {
                        parseMobsArray(zoneObj)
                    } else {
                        // Complex category with subcategories (e.g. catacombs has floor sub-cats)
                        for ((_, subVal) in zoneObj.entrySet()) {
                            try {
                                val subObj = subVal.asJsonObject
                                if (subObj.has("mobs")) {
                                    parseMobsArray(subObj)
                                } else {
                                    for ((_, subSubVal) in subObj.entrySet()) {
                                        try { parseMobsArray(subSubVal.asJsonObject) } catch (_: Exception) {}
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}

                if (entries.isNotEmpty()) result[zoneKey] = entries
            }

            repoData = result
            repoLoaded = true
            FamilyAddons.LOGGER.info("BestiaryZoneHighlight: repo loaded — ${result.size} zones")
            for ((zone, mobs) in result) {
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: zone '$zone' mobs: ${mobs.map { it.displayName }}")
            }

        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("BestiaryZoneHighlight: repo load failed: ${e.message}")
        }
    }

    private fun get(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url))
            .header("User-Agent", "FamilyAddons/1.0").GET().build()
        JsonParser.parseString(httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    } catch (e: Exception) { null }

    private fun getRaw(url: String) = try {
        val req = HttpRequest.newBuilder().uri(URI.create(url))
            .header("User-Agent", "FamilyAddons/1.0").GET().build()
        httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body()
    } catch (e: Exception) { null }
}