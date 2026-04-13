package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.KeyFetcher
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile var activeMobNames: Set<String> = emptySet()
        private set

    private val httpClient = HttpClient.newHttpClient()
    private var tickCounter = 0
    private val fetching = AtomicBoolean(false)

    private data class MobEntry(val displayName: String, val mobIds: List<String>, val maxKills: Long)
    private var repoData: Map<String, List<MobEntry>> = emptyMap()
    private var repoLoaded = false

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
        if (fetching.getAndSet(true)) return
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

                val apiKey = KeyFetcher.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    // No API key — highlight all mobs in zone (can't check if maxed)
                    activeMobNames = zoneMobs.map { mob ->
                        mob.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim().lowercase()
                    }.toSet()
                    MinecraftClient.getInstance().execute { EntityHighlight.rescan() }
                    return@runAsync
                }

                val player = MinecraftClient.getInstance().player ?: return@runAsync
                val uuid = player.gameProfile.id.toString().replace("-", "")

                val data = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid&key=$apiKey")
                    ?: run { activeMobNames = zoneMobs.map { mob ->
                        mob.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim().lowercase()
                    }.toSet()
                        MinecraftClient.getInstance().execute { EntityHighlight.rescan() }
                        return@runAsync }

                if (data.get("success")?.asBoolean != true) { activeMobNames = emptySet(); return@runAsync }

                val profiles = data.getAsJsonArray("profiles") ?: run { activeMobNames = emptySet(); return@runAsync }
                val profile = profiles.map { it.asJsonObject }
                    .firstOrNull { it.get("selected")?.asBoolean == true }
                    ?: profiles.lastOrNull()?.asJsonObject
                    ?: run { activeMobNames = emptySet(); return@runAsync }

                val member = profile.getAsJsonObject("members")?.getAsJsonObject(uuid)
                    ?: run { activeMobNames = emptySet(); return@runAsync }

                val killsObj = member.getAsJsonObject("bestiary")?.getAsJsonObject("kills")

                // Mobs whose in-game entity name differs from the bestiary display name
                val NAME_REMAPS = mapOf(
                    "sneaky creeper" to "creeper"
                )

                val notMaxed = mutableSetOf<String>()
                for (mob in zoneMobs) {
                    if (!isMobMaxed(mob, killsObj)) {
                        val cleanName = mob.displayName
                            .replace(Regex("§[0-9a-fk-or]"), "")
                            .trim()
                            .lowercase()
                        notMaxed.add(NAME_REMAPS[cleanName] ?: cleanName)
                    }
                }
                activeMobNames = notMaxed
                MinecraftClient.getInstance().execute { EntityHighlight.rescan() }
                FamilyAddons.LOGGER.info("BestiaryZoneHighlight: $zoneName → ${notMaxed.size} not-maxed mobs: $notMaxed")
                val maxedMobs = zoneMobs.map { it.displayName.replace(Regex("§[0-9a-fk-or]"), "").trim().lowercase() }.toSet() - notMaxed
                if (maxedMobs.isNotEmpty()) FamilyAddons.LOGGER.info("BestiaryZoneHighlight: already maxed (skipped): $maxedMobs")

            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("BestiaryZoneHighlight error: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun isMobMaxed(mob: MobEntry, killsObj: com.google.gson.JsonObject?): Boolean {
        if (killsObj == null) return false
        val total = mob.mobIds.sumOf { id -> killsObj.get(id)?.asLong ?: 0L }
        return total >= mob.maxKills
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