package org.kyowa.familyaddons.commands

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.features.InfernalKeyTracker
import org.kyowa.familyaddons.features.PartyRepCheck
import org.kyowa.familyaddons.features.Waypoints
import org.kyowa.familyaddons.party.PartyTracker

object TestCommand {

    var openGuiNextTick = false
    var openConfigNextTick = false

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->

            dispatcher.register(
                literal("fa").executes { ctx ->
                    openConfigNextTick = true
                    1
                }
            )

            dispatcher.register(
                literal("fagui").executes { ctx ->
                    openGuiNextTick = true
                    1
                }
            )

            dispatcher.register(
                literal("fakeyrefresh").executes { ctx ->
                    val p = ctx.source.player
                    p.sendMessage(Text.literal("§6[FA] §7Refreshing key tracker..."), false)
                    InfernalKeyTracker.fetchSacks(silent = false)
                    1
                }
            )

            dispatcher.register(
                literal("faparty").executes { ctx ->
                    val p = ctx.source.player
                    val members = PartyTracker.members
                    if (members.isEmpty()) {
                        p.sendMessage(Text.literal("§6[FA] §7No cached party members."), false)
                    } else {
                        p.sendMessage(Text.literal("§6[FA] §eCached party members:"), false)
                        members.forEach { (name, rank) ->
                            val rankStr = if (rank.isNotEmpty()) "§7[§f$rank§7] " else ""
                            val leaderMark = if (PartyTracker.isLeader(name)) " §6(leader)" else ""
                            p.sendMessage(Text.literal("  $rankStr§f$name$leaderMark"), false)
                        }
                    }
                    1
                }
            )

            dispatcher.register(
                literal("fawp")
                    .executes { ctx ->
                        val p = ctx.source.player
                        p.sendMessage(Text.literal("§6[FA] Waypoint commands:"), false)
                        p.sendMessage(Text.literal("  §e/fawp list §7- list waypoints on current island"), false)
                        p.sendMessage(Text.literal("  §e/fawp delete <index> §7- delete by index"), false)
                        p.sendMessage(Text.literal("  §e/fawp clear §7- clear this island"), false)
                        p.sendMessage(Text.literal("  §e/fawp rename <index> <name> §7- rename waypoint"), false)
                        1
                    }
                    .then(literal("list").executes { ctx ->
                        val p = ctx.source.player
                        val island = Waypoints.getCurrentIsland()
                        if (island == null) { p.sendMessage(Text.literal("§c[FA] Can't detect island."), false); return@executes 1 }
                        val wps = Waypoints.getWaypoints(island)
                        if (wps.isEmpty()) { p.sendMessage(Text.literal("§7No waypoints on §e$island§7."), false); return@executes 1 }
                        p.sendMessage(Text.literal("§6Waypoints on §e$island§6:"), false)
                        wps.forEachIndexed { i, wp -> p.sendMessage(Text.literal("  §7[$i] §f${wp.label} §8@ §b${wp.x}, ${wp.y}, ${wp.z}"), false) }
                        1
                    })
                    .then(literal("clear").executes { ctx ->
                        val p = ctx.source.player
                        val island = Waypoints.getCurrentIsland()
                        if (island == null) { p.sendMessage(Text.literal("§c[FA] Can't detect island."), false); return@executes 1 }
                        Waypoints.clearWaypoints(island)
                        p.sendMessage(Text.literal("§a[FA] Cleared all waypoints on §e$island§a."), false)
                        1
                    })
                    .then(literal("delete")
                        .then(argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .executes { ctx ->
                                val p = ctx.source.player
                                val island = Waypoints.getCurrentIsland()
                                if (island == null) { p.sendMessage(Text.literal("§c[FA] Can't detect island."), false); return@executes 1 }
                                val idx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index")
                                if (Waypoints.removeWaypoint(island, idx)) {
                                    p.sendMessage(Text.literal("§a[FA] Deleted waypoint §e$idx§a."), false)
                                } else {
                                    p.sendMessage(Text.literal("§c[FA] No waypoint at index $idx."), false)
                                }
                                1
                            }))
                    .then(literal("rename")
                        .then(argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                            .then(argument("name", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val p = ctx.source.player
                                    val island = Waypoints.getCurrentIsland()
                                    if (island == null) { p.sendMessage(Text.literal("§c[FA] Can't detect island."), false); return@executes 1 }
                                    val idx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index")
                                    val name = StringArgumentType.getString(ctx, "name")
                                    if (Waypoints.renameWaypoint(island, idx, name)) {
                                        p.sendMessage(Text.literal("§a[FA] Renamed waypoint §e$idx§a to §f$name§a."), false)
                                    } else {
                                        p.sendMessage(Text.literal("§c[FA] No waypoint at index $idx."), false)
                                    }
                                    1
                                })))
            )

            dispatcher.register(
                literal("fanpc")
                    .then(argument("name", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val p = ctx.source.player
                            val query = StringArgumentType.getString(ctx, "name")
                            val results = org.kyowa.familyaddons.features.NpcLocations.findNpc(query)
                            if (results.isEmpty()) {
                                p.sendMessage(Text.literal("§c[FA] No NPC found matching '§e$query§c'."), false)
                                return@executes 1
                            }
                            results.forEach { npc ->
                                p.sendMessage(Text.literal("§6[FA] §e${npc.name} §7is in §b${npc.location} §7at §f${npc.x.toInt()}, ${npc.y.toInt()}, ${npc.z.toInt()}"), false)
                                org.kyowa.familyaddons.features.NpcLocations.activeWaypoints.add(
                                    org.kyowa.familyaddons.features.NpcLocations.ActiveNpcWaypoint(npc.name, npc.x, npc.y, npc.z)
                                )
                            }
                            1
                        })
            )

            dispatcher.register(
                literal("fanpcremove").executes { ctx ->
                    org.kyowa.familyaddons.features.NpcLocations.activeWaypoints.clear()
                    ctx.source.player.sendMessage(Text.literal("§6[FA] §7Cleared all NPC waypoints."), false)
                    1
                }
            )

            dispatcher.register(
                literal("facr")
                    .then(argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "name")
                            PartyRepCheck.fetchRep(name)
                            1
                        })
            )

        }
    }

    fun getFormattedName(ign: String): String {
        return try {
            val tabList = MinecraftClient.getInstance().networkHandler?.playerList ?: return "§e$ign"
            for (entry in tabList) {
                val entryName = entry.profile.name ?: continue
                if (entryName.equals(ign, ignoreCase = true)) {
                    val display = entry.displayName?.string ?: continue
                    if (display.contains(ign, ignoreCase = true)) return display.trim()
                }
            }
            "§e$ign"
        } catch (e: Exception) { "§e$ign" }
    }

    private fun flag(b: Boolean) = if (b) "§aON" else "§cOFF"
}
