package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager

object DungeonAutoRequeue {

    private var cancelNextRequeue = false
    private var dtRequester: String? = null
    private var announcePartyMsg: String? = null
    private var announceTicks = 0
    private var requeueTicksLeft = 0
    private var waitingRequeue = false
    var inDungeon = false
    private var checkTicksRemaining = -1

    // After inDungeon confirmed, wait 40 ticks then read tab list for party size
    private var partySizeCheckTicks = -1

    private val DT_PATTERN = Regex(
        """^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*[!.]dt(?:\s.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val UNDT_PATTERN = Regex(
        """^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*[!.]undt\b""",
        RegexOption.IGNORE_CASE
    )
    private val PARTY_SIZE_PATTERN = Regex("""Party\s*\((\d+)\)""")

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            cancelNextRequeue = false
            dtRequester = null
            waitingRequeue = false
            requeueTicksLeft = 0
            inDungeon = false
            checkTicksRemaining = -1
            partySizeCheckTicks = -1
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            checkTicksRemaining = 200
            inDungeon = false
            partySizeCheckTicks = -1
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleMessage(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            DungeonDtTitle.tick()

            if (checkTicksRemaining > 0) {
                checkTicksRemaining--
                if (checkTicksRemaining % 20 == 0) {
                    val lines = DevTools.getScoreboardLines(client)
                    if (lines.any { it.contains("The Catacombs", ignoreCase = true) }) {
                        inDungeon = true
                        checkTicksRemaining = -1
                        partySizeCheckTicks = 40
                    } else if (checkTicksRemaining == 0) {
                        inDungeon = false
                    }
                }
            }

            if (partySizeCheckTicks > 0) {
                partySizeCheckTicks--
                if (partySizeCheckTicks == 0) {
                    checkPartySize(client)
                }
            }

            if (announceTicks > 0) {
                announceTicks--
                if (announceTicks == 0) {
                    announcePartyMsg?.let {
                        MinecraftClient.getInstance().player?.networkHandler?.sendChatMessage("/pc $it requested dt!")
                    }
                    announcePartyMsg = null
                }
            }

            if (waitingRequeue) {
                if (requeueTicksLeft > 0) {
                    requeueTicksLeft--
                } else {
                    waitingRequeue = false
                    if (!cancelNextRequeue) {
                        MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
                    }
                }
            }
        }
    }

    private fun checkPartySize(client: MinecraftClient) {
        if (!FamilyConfigManager.config.dungeons.checkPartySize) return
        val tabList = client.networkHandler?.playerList ?: return
        val partyLine = tabList
            .mapNotNull { it.displayName?.string?.replace(COLOR_CODE_REGEX, "") }
            .firstOrNull { PARTY_SIZE_PATTERN.containsMatchIn(it) }
            ?: return
        val size = PARTY_SIZE_PATTERN.find(partyLine)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (size < 5) {
            cancelNextRequeue = true
            chat("§e[FA] Dungeon requeue cancelled — only $size players in party.")
            DungeonDtTitle.show("§cOnly §e$size §cplayers — §frequeue cancelled!")
        }
    }

    private fun handleMessage(plain: String) {
        val config = FamilyConfigManager.config.dungeons

        val dtMatch = DT_PATTERN.find(plain)
        if (dtMatch != null) {
            val name = dtMatch.groupValues[1].trim()
            if (inDungeon) {
                if (config.dtTitle) DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
                cancelNextRequeue = true
                dtRequester = name
            }
            return
        }

        val undtMatch = UNDT_PATTERN.find(plain)
        if (undtMatch != null) {
            val name = undtMatch.groupValues[1].trim()
            if (inDungeon) {
                if (config.dtTitle) DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
                cancelNextRequeue = false
                dtRequester = null
                announcePartyMsg = null
                announceTicks = 0
            }
            return
        }

        if (!config.autoRequeue) return

        if (plain.contains("> EXTRA STATS <")) {
            if (!inDungeon) return
            inDungeon = false

            if (cancelNextRequeue) {
                cancelNextRequeue = false
                if (dtRequester != null) {
                    announcePartyMsg = dtRequester
                    announceTicks = 40
                    dtRequester = null
                }
                return
            }

            val delay = (config.requeueDelaySecs * 20).toInt()
            if (delay <= 0) {
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
            } else {
                waitingRequeue = true
                requeueTicksLeft = delay
            }
            return
        }

        if (plain.contains("left the party", ignoreCase = true) && inDungeon) {
            cancelNextRequeue = true
            chat("§e[FA] Party member left — dungeon requeue cancelled.")
        }
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }
}