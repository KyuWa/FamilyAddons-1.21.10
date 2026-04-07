package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.config.FamilyConfigManager
import java.util.concurrent.CompletableFuture

object FarmCalcCommand {

    private const val MAX_XP     = 25_000_000.0  // XP to reach lvl 100
    private const val SLOT_SHARE = 0.47           // each slot pet gets 47% independently
    private const val AH_TAX     = 0.025          // 2.5% AH listing tax

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("fafc")
                    .executes { _ ->
                        CompletableFuture.runAsync { runCalc() }
                        1
                    }
                    .then(literal("start").executes { _ ->
                        ForagingTracker.start(); 1
                    })
                    .then(literal("stop").executes { _ ->
                        ForagingTracker.stop(); 1
                    })
                    .then(literal("reset").executes { _ ->
                        ForagingTracker.reset()
                        send("§6[FA] §7Tracker reset.")
                        1
                    })
                    .then(literal("status").executes { _ ->
                        showStatus(); 1
                    })
            )
        }
    }

    private fun showStatus() {
        val tracker = ForagingTracker
        val cfg = FamilyConfigManager.config.farmCalc
        val foragingWisdom = cfg.foragingWisdom.toDoubleOrNull() ?: 0.0
        val tamingWisdom   = cfg.tamingWisdom.toDoubleOrNull()   ?: 0.0
        val treeGiftAvg    = cfg.treeGiftAvgXp.toDoubleOrNull()  ?: 0.0

        send("§6§l[FA] Foraging Tracker Status")
        send("§7Tracking: ${if (tracker.isTracking) "§aON" else "§cOFF"}")
        send("§7Session: §e${"%.1f".format(tracker.sessionDurationMinutes())}m")
        send("§7Total XP gained: §e${ForagingTracker.formatXp(tracker.xpPerHour * tracker.sessionDurationMinutes() / 60.0)}")
        send("§7Tree Gifts detected: §e${tracker.treeGiftCount}")
        send("§7Live XP/hr: §e${ForagingTracker.formatXp(tracker.xpPerHour)}")
        send("§7Foraging Wisdom: §e$foragingWisdom §7| Taming Wisdom: §e$tamingWisdom")
        if (treeGiftAvg > 0) send("§7Avg Tree Gift XP: §e${ForagingTracker.formatXp(treeGiftAvg)} §7per gift")
    }

    private fun runCalc() {
        val cfg = FamilyConfigManager.config.farmCalc
        val foragingWisdom = cfg.foragingWisdom.toDoubleOrNull() ?: 0.0
        val tamingWisdom   = cfg.tamingWisdom.toDoubleOrNull()   ?: 0.0
        val treeGiftAvg    = cfg.treeGiftAvgXp.toDoubleOrNull()  ?: 0.0

        // Determine XP/hr source
        val trackedXpHr = ForagingTracker.xpPerHour
        val manualXpHr  = cfg.xpPerHour.toDoubleOrNull()?.let { it * 1_000_000.0 }
        val xpPerHour: Double
        val xpSource: String

        when {
            trackedXpHr > 10_000 -> {
                // Tracker has live data — also add tree gift bonus
                val giftBonusPerHr = if (treeGiftAvg > 0 && ForagingTracker.treeGiftCount > 0) {
                    val giftsPerHr = ForagingTracker.treeGiftCount / (ForagingTracker.sessionDurationMinutes() / 60.0).coerceAtLeast(0.01)
                    giftsPerHr * treeGiftAvg
                } else 0.0
                xpPerHour = trackedXpHr + giftBonusPerHr
                xpSource = "§aLive tracker"
            }
            manualXpHr != null && manualXpHr > 0 -> {
                xpPerHour = manualXpHr
                xpSource = "§eManual config"
            }
            else -> {
                send("§6[FA] §cNo XP data. Run §f/fafc start §cwhile foraging, or set XP/hr in config.")
                return
            }
        }

        // Each slot pet gets 47% of total XP/hr
        // Taming wisdom gives +1% pet XP per point, so slot pets get:
        // xpPerPetPerHour = xpPerHour * SLOT_SHARE * (1 + tamingWisdom/100)
        val tamingMultiplier    = 1.0 + tamingWisdom / 100.0
        val xpPerPetPerHour     = xpPerHour * SLOT_SHARE * tamingMultiplier

        val petEntries = listOf(
            1 to cfg.slotPet1.trim(),
            2 to cfg.slotPet2.trim(),
            3 to cfg.slotPet3.trim()
        ).filter { it.second.isNotEmpty() }

        if (petEntries.isEmpty()) {
            send("§6[FA] §cNo pets set. Set them in /fagui → Farm Calc.")
            return
        }

        val lowestBin = ItemPrices.getLowestBin()
        if (lowestBin.isEmpty()) {
            send("§6[FA] §cPrice data not loaded yet. Try again in a few seconds.")
            return
        }

        data class PetResult(
            val slot: Int, val name: String, val rarity: String,
            val lvl1: Double, val lvlMax: Double,
            val hoursToMax: Double, val coinsPerHour: Double
        )

        val rarityNames = mapOf(0 to "COMMON", 1 to "UNCOMMON", 2 to "RARE", 3 to "EPIC", 4 to "LEGENDARY", 5 to "MYTHIC")
        val rarityColors = mapOf(
            "COMMON" to "§f", "UNCOMMON" to "§a", "RARE" to "§9",
            "EPIC" to "§5", "LEGENDARY" to "§6", "MYTHIC" to "§d"
        )

        val results = mutableListOf<PetResult>()

        for ((slot, rawName) in petEntries) {
            val petKey = rawName.uppercase().replace(" ", "_")
            var found: PetResult? = null

            for (rarity in listOf(4, 5, 3, 2, 1, 0)) {
                val lvl1Price = lowestBin["$petKey;$rarity"]      ?: continue
                val maxPrice  = lowestBin["$petKey;$rarity+100"]  ?: continue
                val profit    = (maxPrice - lvl1Price) * (1.0 - AH_TAX)
                if (profit <= 0) continue
                val hoursToMax   = MAX_XP / xpPerPetPerHour
                val coinsPerHour = profit / hoursToMax
                found = PetResult(slot, rawName, rarityNames[rarity] ?: "?", lvl1Price, maxPrice, hoursToMax, coinsPerHour)
                break
            }

            if (found == null) {
                send("§6[FA] §cSlot $slot: §f$rawName §cnot found in price data.")
            } else {
                results.add(found)
            }
        }

        val totalPerHour = results.sumOf { it.coinsPerHour }

        MinecraftClient.getInstance().execute {
            val player = MinecraftClient.getInstance().player ?: return@execute

            player.sendMessage(Text.literal("§6§l[FA] Farm Calc  §8($xpSource§8)"), false)
            player.sendMessage(Text.literal(buildString {
                append("§7XP/hr: §e${ForagingTracker.formatXp(xpPerHour)}")
                if (tamingWisdom > 0) append("  §7Taming: §e+${"%.0f".format(tamingWisdom)}%")
                append("  §7Pet XP/hr: §e${ForagingTracker.formatXp(xpPerPetPerHour)}")
            }), false)

            // Show wisdom breakdown
            if (foragingWisdom > 0) {
                val baseXp = xpPerHour / (1.0 + foragingWisdom / 100.0)
                val wisdomBonus = xpPerHour - baseXp
                player.sendMessage(Text.literal(
                    "§7Foraging Wisdom §e+${"%.0f".format(foragingWisdom)}%§7: base §f${ForagingTracker.formatXp(baseXp)}§7 + §a${ForagingTracker.formatXp(wisdomBonus)}§7 bonus"
                ), false)
            }

            if (ForagingTracker.treeGiftCount > 0 && treeGiftAvg > 0) {
                val giftsPerHr = ForagingTracker.treeGiftCount / (ForagingTracker.sessionDurationMinutes() / 60.0).coerceAtLeast(0.01)
                player.sendMessage(Text.literal(
                    "§7Tree Gifts: §e${ForagingTracker.treeGiftCount} §7detected (§e${"%.1f".format(giftsPerHr)}/hr§7)"
                ), false)
            }

            player.sendMessage(Text.literal("§8§m                                        "), false)

            for (r in results) {
                val color  = rarityColors[r.rarity] ?: "§f"
                val profitAfterTax = (r.lvlMax - r.lvl1) * (1.0 - AH_TAX)
                player.sendMessage(Text.literal("§eSlot ${r.slot}: $color${r.name} §7(${r.rarity})"), false)
                player.sendMessage(Text.literal("  §7Lvl 1: §f${formatCoins(r.lvl1)}  §8→  §7Lvl 100: §f${formatCoins(r.lvlMax)}"), false)
                player.sendMessage(Text.literal("  §7Profit after tax: §a+${formatCoins(profitAfterTax)}"), false)
                player.sendMessage(Text.literal("  §7Hours to lvl 100: §f${"%.1f".format(r.hoursToMax)}h  §8|  §7Coins/hr: §a+${formatCoins(r.coinsPerHour)}"), false)
            }

            player.sendMessage(Text.literal("§8§m                                        "), false)
            player.sendMessage(Text.literal("§6§lTotal: §a§l+${formatCoins(totalPerHour)} coins/hr §7(${results.size} pets)"), false)
        }
    }

    private fun send(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }

    private fun formatCoins(value: Double): String = when {
        value >= 1_000_000_000 -> "%.2fb".format(value / 1_000_000_000)
        value >= 1_000_000     -> "%.2fm".format(value / 1_000_000)
        value >= 1_000         -> "%.1fk".format(value / 1_000)
        else                   -> "%.0f".format(value)
    }
}
