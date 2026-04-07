package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.party.PartyTracker

object AutoRequeue {

    private var cancelNextRequeue = false
    private var currentTier       = "infernal"
    private var diedThisRun       = false
    private var cancelReason      = ""
    private var dtRequester: String? = null
    private var announcePartyMsg: String? = null
    private var announceTicks     = 0
    private var waitingRequeue    = false
    private var requeueTicksLeft  = 0
    var inKuudra                  = false

    private var checkTicksRemaining = -1

    private val DT_PATTERN = Regex(
        """^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*[!.]dt(?:\s.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val TIER_PATTERN = Regex("""(Basic|Hot|Burning|Fiery|Infernal) Tier""", RegexOption.IGNORE_CASE)
    private val UNDT_PATTERN = Regex(
        """^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([A-Za-z0-9_]{3,16})\s*:\s*[!.]undt\b""",
        RegexOption.IGNORE_CASE
    )

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            inKuudra = false
            checkTicksRemaining = -1
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            checkTicksRemaining = 200
            inKuudra = false
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleMessage(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            DtTitle.tick()

            if (checkTicksRemaining > 0) {
                checkTicksRemaining--
                if (checkTicksRemaining % 20 == 0) {
                    val lines = DevTools.getScoreboardLines(client)
                    if (lines.any { it.contains("Kuudra", ignoreCase = true) }) {
                        inKuudra = true
                        checkTicksRemaining = -1
                        FamilyAddons.LOGGER.info("[FA] Kuudra detected via scoreboard")
                    } else if (checkTicksRemaining == 0) {
                        inKuudra = false
                        FamilyAddons.LOGGER.info("[FA] Not in Kuudra after 10s check")
                    }
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

    private fun handleMessage(plain: String) {
        val config = FamilyConfigManager.config.kuudra
        val player = MinecraftClient.getInstance().player ?: return
        val selfName = player.name.string

        val dtMatch = DT_PATTERN.find(plain)
        if (dtMatch != null) {
            val name = dtMatch.groupValues[1].trim()
            FamilyAddons.LOGGER.info("[FA] Kuudra !dt detected from '$name' | inKuudra=$inKuudra | plain='$plain'")
            if (inKuudra) {
                if (config.dtTitle) DtTitle.show("§e$name §crequested §fDT!")
                cancelNextRequeue = true
                dtRequester = name
                cancelReason = "dt"
            }
            return
        }

        val undtMatch = UNDT_PATTERN.find(plain)
        if (undtMatch != null) {
            val name = undtMatch.groupValues[1].trim()
            FamilyAddons.LOGGER.info("[FA] Kuudra !undt detected from '$name' | inKuudra=$inKuudra")
            if (inKuudra) {
                if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
                cancelNextRequeue = false
                cancelReason = ""
                dtRequester = null
                announcePartyMsg = null
                announceTicks = 0
            }
            return
        }

        if (!config.autoRequeue) return

        val tierMatch = TIER_PATTERN.find(plain)
        if (tierMatch != null && plain.contains("Tier")) {
            currentTier = tierMatch.groupValues[1].lowercase()
            cancelNextRequeue = false
            inKuudra = true
            return
        }

        if (plain.contains("Okay adventurers, I will go and fish up Kuudra")) {
            inKuudra = true
            checkPartySize()
            return
        }

        if (plain == "KUUDRA DOWN!") {
            if (cancelNextRequeue) {
                cancelNextRequeue = false
                if (cancelReason == "dt" && dtRequester != null) {
                    announcePartyMsg = dtRequester
                    announceTicks = 40
                }
                dtRequester = null
                cancelReason = ""
                return
            }
            val tierAllowed = when (currentTier) {
                "basic"    -> config.requeueBasic
                "hot"      -> config.requeueHot
                "burning"  -> config.requeueBurning
                "fiery"    -> config.requeueFiery
                "infernal" -> config.requeueInfernal
                else -> false
            }
            if (!tierAllowed) return
            if (diedThisRun) {
                diedThisRun = false
                waitingRequeue = true
                requeueTicksLeft = 40
            } else {
                player.networkHandler.sendChatCommand("instancerequeue")
            }
            return
        }

        if (plain.contains("left the party", ignoreCase = true) && inKuudra) {
            cancelNextRequeue = true
            cancelReason = "leave"
            dtRequester = null
            chat("§e[FA] Party member left — requeue cancelled.")
            return
        }

        if (plain == "$selfName was FINAL KILLED by Kuudra!") {
            diedThisRun = true
        }
    }

    private fun checkPartySize() {
        if (!FamilyConfigManager.config.kuudra.checkPartySize) return
        val size = PartyTracker.members.size
        FamilyAddons.LOGGER.info("[FA] Party size at run start: $size")
        if (size in 1..3) {
            cancelNextRequeue = true
            cancelReason = "size"
            chat("§e[FA] Auto requeue disabled — only $size players in party.")
            DtTitle.show("§cOnly §e$size §cplayers — §frequeue cancelled!")
        }
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }
}