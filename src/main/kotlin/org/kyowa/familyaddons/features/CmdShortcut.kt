package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import org.kyowa.familyaddons.config.FamilyConfigManager

object CmdShortcut {

    private data class Shortcut(val name: String, val target: String)

    private val shortcuts = listOf(
        Shortcut("museum", "warp museum"),
        Shortcut("pw",     "p warp"),
        Shortcut("koff",   "p kickoffline"),
        Shortcut("t5", "joininstance kuudra_infernal"),
        Shortcut("t4", "joininstance kuudra_fiery"),
        Shortcut("t3", "joininstance kuudra_burning"),
        Shortcut("t2", "joininstance kuudra_hot"),
        Shortcut("t1", "joininstance kuudra_normal")
    )

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (sc in shortcuts) {
                dispatcher.register(
                    literal(sc.name).executes { ctx ->
                        if (!FamilyConfigManager.config.utilities.commandShortcuts) return@executes 0
                        ctx.source.player.networkHandler.sendChatCommand(sc.target)
                        1
                    }
                )
            }
        }
    }
}
