package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.FamilyAddons
import java.util.concurrent.CompletableFuture

object PetValueCommand {

    private val LVL200_PETS = setOf("GOLDEN_DRAGON")
    private val EXCLUDED_PETS = setOf("GOLDEN_DRAGON", "ROSE_DRAGON", "JADE_DRAGON")

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("fatvom").executes { _ ->
                    CompletableFuture.runAsync { runCheck() }
                    1
                }
            )
        }
    }

    private fun runCheck() {
        val player = MinecraftClient.getInstance().player ?: return

        MinecraftClient.getInstance().execute {
            player.sendMessage(Text.literal("§6[FA] §eSearching for best pet flip values..."), false)
        }

        // Give ItemPrices a moment to ensure data is loaded
        val lowestBin = ItemPrices.getLowestBin()

        if (lowestBin.isEmpty()) {
            MinecraftClient.getInstance().execute {
                player.sendMessage(Text.literal("§6[FA] §cPrice data not loaded yet. Try again in a few seconds."), false)
            }
            return
        }

        // Find all lvl1 keys (format: TYPE;RARITY — no +100/+200 suffix)
        // and match with their lvl100/200 counterparts
        data class PetFlip(val name: String, val rarity: String, val lvl1: Double, val lvlMax: Double, val profit: Double)

        val rarityNames = mapOf(0 to "COMMON", 1 to "UNCOMMON", 2 to "RARE", 3 to "EPIC", 4 to "LEGENDARY", 5 to "MYTHIC")
        val rarityColors = mapOf(
            "COMMON" to "§f", "UNCOMMON" to "§a", "RARE" to "§9",
            "EPIC" to "§5", "LEGENDARY" to "§6", "MYTHIC" to "§d"
        )

        val flips = mutableListOf<PetFlip>()

        for ((key, lvl1Price) in lowestBin) {
            // Only process pet keys — format is "TYPE;RARITY" (number 0-5)
            val parts = key.split(";")
            if (parts.size != 2) continue
            val petType = parts[0]
            val rarityStr = parts[1]
            val rarityNum = rarityStr.toIntOrNull() ?: continue

            // Only legendary (4) and mythic (5)
            if (rarityNum < 4) continue

            // Skip if it has a + suffix (those are the max level entries)
            if (rarityStr.contains("+")) continue

            if (petType in EXCLUDED_PETS) continue
            val isLvl200 = petType in LVL200_PETS
            val maxKey = "$petType;$rarityNum+${if (isLvl200) 200 else 100}"
            val maxPrice = lowestBin[maxKey] ?: continue

            val profit = maxPrice - lvl1Price
            if (profit <= 0) continue

            val rarityName = rarityNames[rarityNum] ?: continue
            val displayName = petType.replace("_", " ").lowercase()
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            flips.add(PetFlip(displayName, rarityName, lvl1Price, maxPrice, profit))
        }

        // Sort by profit descending, take top 5
        val top5 = flips.sortedByDescending { it.profit }.take(5)

        MinecraftClient.getInstance().execute {
            if (top5.isEmpty()) {
                player.sendMessage(Text.literal("§6[FA] §cNo pet flip data found."), false)
                return@execute
            }

            player.sendMessage(Text.literal("§6§l[FA] Top 5 Pet Flips (Lvl 1 → Max):"), false)
            player.sendMessage(Text.literal("§8§m                                        "), false)

            top5.forEachIndexed { i, flip ->
                val color = rarityColors[flip.rarity] ?: "§f"
                val num = "§e${i + 1}. "
                val name = "$color${flip.name} §7(${flip.rarity})"
                val lvl1Fmt = formatCoins(flip.lvl1)
                val maxFmt  = formatCoins(flip.lvlMax)
                val profFmt = formatCoins(flip.profit)

                player.sendMessage(Text.literal("$num$name"), false)
                player.sendMessage(Text.literal("   §7Lvl 1: §f$lvl1Fmt  §8→  §7Max: §f$maxFmt"), false)
                player.sendMessage(Text.literal("   §aProfit: §2+$profFmt coins"), false)
            }

            player.sendMessage(Text.literal("§8§m                                        "), false)
        }
    }

    private fun formatCoins(value: Double): String {
        return when {
            value >= 1_000_000_000 -> "%.1fb".format(value / 1_000_000_000)
            value >= 1_000_000     -> "%.1fm".format(value / 1_000_000)
            value >= 1_000         -> "%.1fk".format(value / 1_000)
            else                   -> "%.0f".format(value)
        }
    }
}


