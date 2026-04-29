package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.scoreboard.Team
import net.minecraft.text.Text
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager

object AutoRequeue {

    private val TIER_PATTERN = Regex(
        """(?:\[[^\]]+\]\s+)?(\w+)\s+entered Kuudra's Hollow, (Basic|Hot|Burning|Fiery|Infernal) Tier!""",
        RegexOption.IGNORE_CASE
    )

    // ── Kuudra state ──────────────────────────────────────────
    private var inKuudra              = false
    private var kuudraTier            = "infernal"
    private var kuudraCancelRequeue   = false
    private var kuudraDtRequester: String? = null
    private var kuudraDtAnnounceName: String? = null
    private var kuudraDtAnnounceTicks = 0
    private var kuudraDiedThisRun     = false
    private var kuudraWaiting         = false
    private var kuudraWaitTicks       = 0

    // ── Tablist-driven area detection ─────────────────────────
    @Volatile private var inKuudraArea = false
    private var areaCheckTicker = 0

    // ── Dungeon state ─────────────────────────────────────────
    private val dungeonNeedsDowntime  = java.util.Collections.newSetFromMap<String>(java.util.concurrent.ConcurrentHashMap())
    private var inDungeon             = false
    private var checkTicksRemaining   = -1
    private var dungeonRequeueTicks   = 0

    // ── Public accessors ──────────────────────────────────────
    fun isInKuudra(): Boolean = inKuudra || inKuudraArea
    fun isInKuudraArea(): Boolean = inKuudraArea
    fun chatTriggerActive(): Boolean = inKuudra

    /**
     * Returns the current Kuudra tier as a 1-based index:
     *   1 = Basic, 2 = Hot, 3 = Burning, 4 = Fiery, 5 = Infernal
     * Defaults to 5 (Infernal) when no tier message has been seen this session.
     */
    fun kuudraTierIndex(): Int = when (kuudraTier) {
        "basic"    -> 1
        "hot"      -> 2
        "burning"  -> 3
        "fiery"    -> 4
        "infernal" -> 5
        else       -> 5
    }

    // ── Reset variants ────────────────────────────────────────
    /**
     * Full wipe — used only on actual server disconnect. Resets the captured
     * Kuudra tier back to default Infernal, since we're starting a fresh
     * session and have no idea what tier the next run will be.
     */
    private fun resetAllOnDisconnect() {
        inKuudra               = false
        kuudraTier             = "infernal"   // full reset
        kuudraCancelRequeue    = false
        kuudraDtRequester      = null
        kuudraDtAnnounceName   = null
        kuudraDtAnnounceTicks  = 0
        kuudraDiedThisRun      = false
        kuudraWaiting          = false
        kuudraWaitTicks        = 0

        inKuudraArea           = false
        areaCheckTicker        = 0

        inDungeon              = false
        dungeonNeedsDowntime.clear()
        dungeonRequeueTicks    = 0
        checkTicksRemaining    = -1
    }

    /**
     * Soft reset — used on world JOIN (which fires every time you swap worlds
     * within the same server, including teleporting into Kuudra arena). Hypixel
     * sends the "X entered Kuudra's Hollow, TIER!" chat message AROUND the
     * teleport — sometimes BEFORE the JOIN fires, in which case a full reset
     * here would wipe the captured tier and stick us at the default Infernal
     * (which was the bug).
     *
     * So this version preserves [kuudraTier] and trusts the chat capture or
     * the next chat message. Only resets transient run-state like death flag
     * and dt requester.
     */
    private fun resetTransientOnJoin() {
        // PRESERVE: kuudraTier (might have been captured by chat just before this fires)
        // PRESERVE: inKuudra (will be re-validated by the area check next tick)
        // PRESERVE: inKuudraArea (will be re-checked on next tickKuudraArea)

        kuudraCancelRequeue    = false
        kuudraDtRequester      = null
        kuudraDtAnnounceName   = null
        kuudraDtAnnounceTicks  = 0
        kuudraDiedThisRun      = false
        kuudraWaiting          = false
        kuudraWaitTicks        = 0

        // Don't reset areaCheckTicker — let the next tick run the area check naturally.

        inDungeon              = false
        dungeonNeedsDowntime.clear()
        dungeonRequeueTicks    = 0
        checkTicksRemaining    = -1
    }

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetAllOnDisconnect() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            resetTransientOnJoin()
            checkTicksRemaining = 200
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val raw = message.string
            val plain = raw.replace(COLOR_CODE_REGEX, "").trim()
            handleKuudra(raw, plain)
            handleDungeon(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            DtTitle.tick()
            DungeonDtTitle.tick()
            tickKuudraArea(client)
            tickKuudra()
            tickDungeon(client)
        }
    }

    private fun tickKuudraArea(client: MinecraftClient) {
        areaCheckTicker++
        if (areaCheckTicker < 10) return
        areaCheckTicker = 0

        val tablistHit = client.networkHandler?.playerList?.any { entry ->
            val display = entry.displayName?.string ?: return@any false
            val team = client.world?.scoreboard?.getScoreHolderTeam(entry.profile.name)
            val full = Team.decorateName(team, Text.literal(display)).string
            val clean = full.replace(COLOR_CODE_REGEX, "").trim()
            clean.startsWith("Area:", ignoreCase = true) &&
                    clean.substringAfter(":").trim().equals("Kuudra", ignoreCase = true)
        } ?: false

        val sidebarHit = if (tablistHit) true else {
            DevTools.getScoreboardLines(client).any { line ->
                line.contains("Kuudra's Hollow", ignoreCase = true)
            }
        }

        val nowInArea = tablistHit || sidebarHit
        if (nowInArea != inKuudraArea) {
            inKuudraArea = nowInArea
            if (!nowInArea) inKuudra = false
        }
    }

    private fun tickKuudra() {
        if (kuudraDtAnnounceTicks > 0) {
            kuudraDtAnnounceTicks--
            if (kuudraDtAnnounceTicks == 0) {
                kuudraDtAnnounceName?.let {
                    MinecraftClient.getInstance().player?.networkHandler?.sendChatMessage("/pc $it requested dt!")
                }
                kuudraDtAnnounceName = null
            }
        }
        if (kuudraWaiting) {
            if (kuudraWaitTicks > 0) {
                kuudraWaitTicks--
            } else {
                kuudraWaiting = false
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
            }
        }
    }

    private fun tickDungeon(client: MinecraftClient) {
        if (checkTicksRemaining > 0) {
            checkTicksRemaining--
            if (checkTicksRemaining % 20 == 0) {
                val lines = DevTools.getScoreboardLines(client)
                if (lines.any { it.contains("The Catacombs", ignoreCase = true) }) {
                    inDungeon = true
                    checkTicksRemaining = -1
                } else if (checkTicksRemaining == 0) {
                    inDungeon = false
                }
            }
        }
        if (dungeonRequeueTicks > 0) {
            dungeonRequeueTicks--
            if (dungeonRequeueTicks == 0) {
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
            }
        }
    }

    private fun handleKuudra(raw: String, plain: String) {
        val config = FamilyConfigManager.config.kuudra
        val player = MinecraftClient.getInstance().player ?: return
        val selfName = player.name.string

        val tierMatch = TIER_PATTERN.find(plain)
        if (tierMatch != null) {
            kuudraTier          = tierMatch.groupValues[2].lowercase()
            kuudraCancelRequeue = false
            kuudraDiedThisRun   = false
            inKuudra            = true
            return
        }

        val partyMatch = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).find(plain)
        if (partyMatch != null) {
            val name = partyMatch.groupValues[1].trim()
            val msg  = partyMatch.groupValues[2].trim().lowercase()

            if (msg == "!dt" || msg == "dt" || msg.startsWith("!dt")) {
                if (isInKuudra()) {
                    if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
                    kuudraCancelRequeue = true
                    kuudraDtRequester   = name
                    kuudraWaiting       = false
                    kuudraWaitTicks     = 0
                }
                return
            }

            if (msg == "!undt" || msg == "undt") {
                if (isInKuudra()) {
                    if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
                    kuudraCancelRequeue   = false
                    kuudraDtRequester     = null
                    kuudraDtAnnounceName  = null
                    kuudraDtAnnounceTicks = 0
                }
                return
            }
            return
        }

        if (!config.autoRequeue) return

        if (plain == "KUUDRA DOWN!" && !raw.contains(" >") && !raw.contains(":")) {
            if (!isInKuudra()) return
            inKuudra = false

            if (kuudraCancelRequeue) {
                kuudraCancelRequeue = false
                kuudraWaiting       = false
                kuudraWaitTicks     = 0
                if (kuudraDtRequester != null) {
                    kuudraDtAnnounceName  = kuudraDtRequester
                    kuudraDtAnnounceTicks = 40
                }
                kuudraDtRequester = null
                return
            }

            val tierAllowed = when (kuudraTier) {
                "basic"    -> config.requeueBasic
                "hot"      -> config.requeueHot
                "burning"  -> config.requeueBurning
                "fiery"    -> config.requeueFiery
                else       -> config.requeueInfernal
            }
            if (!tierAllowed) return

            if (kuudraDiedThisRun) {
                kuudraDiedThisRun = false
                kuudraWaiting     = true
                kuudraWaitTicks   = 40
            } else {
                player.networkHandler.sendChatCommand("instancerequeue")
            }
            return
        }

        if (plain.contains("left the party", ignoreCase = true) && isInKuudra()) {
            kuudraCancelRequeue = true
            kuudraDtRequester   = null
            chat("§e[FA] Party member left — Kuudra requeue cancelled.")
            return
        }

        if (plain == "$selfName was FINAL KILLED by Kuudra!") {
            kuudraDiedThisRun = true
        }
    }

    private fun handleDungeon(plain: String) {
        val config = FamilyConfigManager.config.dungeons
        val player = MinecraftClient.getInstance().player ?: return

        val partyMatch = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*(.+)$""", RegexOption.IGNORE_CASE).find(plain)
        if (partyMatch != null) {
            val name = partyMatch.groupValues[1].trim()
            val msg  = partyMatch.groupValues[2].trim().lowercase()

            if (msg == "!r" || msg == "r" || msg == "!undt" || msg == "undt") {
                if (!dungeonNeedsDowntime.remove(name)) return
                if (dungeonNeedsDowntime.isEmpty()) {
                    dungeonRequeueTicks = (config.requeueDelaySecs * 20).toInt().coerceAtLeast(1)
                }
                return
            }

            if (msg == "!dt" || msg == "dt" || msg.startsWith("!dt")) {
                if (config.dtTitle) DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
                dungeonNeedsDowntime.add(name)
                return
            }

            return
        }

        if (plain.contains("left the party", ignoreCase = true)) {
            dungeonNeedsDowntime.clear()
            dungeonRequeueTicks = 0
            return
        }

        if (!config.autoRequeue) return

        if (Regex("""^ *> EXTRA STATS <$""").matches(plain)) {
            if (!inDungeon) return
            inDungeon = false
            if (dungeonNeedsDowntime.isEmpty()) {
                dungeonRequeueTicks = (config.requeueDelaySecs * 20).toInt().coerceAtLeast(1)
            } else {
                player.networkHandler.sendChatMessage("/pc ${dungeonNeedsDowntime.joinToString(", ")} needs downtime")
                dungeonNeedsDowntime.clear()
            }
            return
        }
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }
}