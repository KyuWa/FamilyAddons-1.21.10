package org.kyowa.familyaddons.commands

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.Parkour

object ParkourCommand {

    private fun devOnly(block: () -> Unit) {
        if (!FamilyConfigManager.config.parkour.developerMode) {
            MinecraftClient.getInstance().player?.sendMessage(
                net.minecraft.text.Text.literal("§6[FA] §cDeveloper mode required. Enable it in §e/fa §c→ Parkour."),
                false
            )
            return
        }
        block()
    }

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("faparkour")
                    // Always available
                    .then(literal("start")
                        .then(argument("name", StringArgumentType.word()).executes {
                            Parkour.start(StringArgumentType.getString(it, "name")); 1
                        })
                        .executes {
                            Parkour.start(FamilyConfigManager.config.parkour.activeParkour); 1
                        })
                    .then(literal("stop").executes { Parkour.stop(); 1 })
                    .then(literal("select")
                        .then(argument("name", StringArgumentType.word()).executes {
                            Parkour.selectParkour(StringArgumentType.getString(it, "name")); 1
                        }))
                    .then(literal("list").executes { Parkour.listParkours(); 1 })
                    // Dev only — always registered but check at runtime
                    .then(literal("add").executes {
                        devOnly {
                            val p = MinecraftClient.getInstance().player ?: return@devOnly
                            Parkour.addCheckpoint(p.x, p.y + p.standingEyeHeight, p.z, p.yaw, p.pitch)
                        }; 1
                    })
                    .then(literal("removelast").executes { devOnly { Parkour.removeLast() }; 1 })
                    .then(literal("clear").executes { devOnly { Parkour.clearAll() }; 1 })
                    .then(literal("edit")
                        .then(argument("name", StringArgumentType.word()).executes {
                            devOnly { Parkour.edit(StringArgumentType.getString(it, "name")) }; 1
                        })
                        .executes { devOnly { Parkour.edit() }; 1 })
                    .then(literal("listcps").executes { devOnly { Parkour.listCheckpoints() }; 1 })
                    .then(literal("resetbest").executes { devOnly { Parkour.resetBest() }; 1 })
                    .then(literal("delete")
                        .then(argument("name", StringArgumentType.word()).executes {
                            devOnly { Parkour.deleteParkour(StringArgumentType.getString(it, "name")) }; 1
                        }))
                    .executes { Parkour.listParkours(); 1 }
            )
        }
    }
}
