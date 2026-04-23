package org.kyowa.familyaddons

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import org.kyowa.familyaddons.commands.ParkourCommand
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.kyowa.familyaddons.features.*
import org.kyowa.familyaddons.party.PartyTracker
import org.slf4j.LoggerFactory

val COLOR_CODE_REGEX = Regex("§.")

object FamilyAddons : ClientModInitializer {

    val LOGGER = LoggerFactory.getLogger("FamilyAddons")
    const val VERSION = "1.0.7"
    const val MC_VERSION = "1.21.10"

    private var hudEditorMouseWasDown = false

    override fun onInitializeClient() {
        LOGGER.info("FamilyAddons $VERSION loading...")

        FamilyConfigManager.load()
        KeyFetcher.fetchIfNeeded()
        TestCommand.register()

        registerFeatures()
        registerTickEvents()

        LOGGER.info("FamilyAddons $VERSION loaded!")
    }

    private fun registerFeatures() {
        //Auto-Updater
        AutoUpdater.register()

        // Chat
        HideMessages.register()

        // Utilities
        CmdShortcut.register()
        SignMath.register()
        ItemPrices.register()
        GfsKeybinds.register()
        ArachneTimer.register()



        // Party
        PartyTracker.register()
        PartyCommands.register()
        PartyRepCheck.register()

        // Rendering & World
        Waypoints.register()
        NpcLocations.register()
        EntityHighlight.register()
        CorpseESP.register()
        WorldScanner.register()

        // Parkour
        Parkour.register()
        ParkourCommand.register()

        // Kuudra
        DtTitle.register()
        AutoRequeue.register()
        InfernalKeyTracker.register()

        // Dungeons
        DungeonDtTitle.register()

        // Mining
        PickaxeAbility.register()

        // Bestiary
        BestiaryTracker.register()
        BestiaryZoneHighlight.register()

        // Player Disguise (config-driven, no register needed — mixin reads config directly)
        // PlayerDisguise is passive; mixins call PlayerDisguise.isEnabled() / getMobId() / includesSelf()
        SharedDisguiseSync.register()

        // Dev
        DevTools.register()

        // One-off events
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            EntityHighlight.rescan()
            BestiaryZoneHighlight.refresh()
            SharedDisguiseSync.fetchAllNow() // Re-fetch disguises on every server join so they load immediately
        }
    }

    private fun registerTickEvents() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (TestCommand.openGuiNextTick) {
                TestCommand.openGuiNextTick = false
                client.setScreen(HudEditorScreen())
            }
            if (TestCommand.openConfigNextTick) {
                TestCommand.openConfigNextTick = false
                FamilyConfigManager.openGui()
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val screen = client.currentScreen as? HudEditorScreen ?: run {
                hudEditorMouseWasDown = false
                return@register
            }
            val mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                client.window.handle,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS
            val mx = client.mouse.x / client.window.scaleFactor
            val my = client.mouse.y / client.window.scaleFactor
            if (mouseDown && !hudEditorMouseWasDown) screen.onMousePress(mx, my)
            else if (!mouseDown && hudEditorMouseWasDown) screen.onMouseRelease()
            hudEditorMouseWasDown = mouseDown
        }

        LOGGER.info("FamilyAddons $VERSION loaded!")
    }
}