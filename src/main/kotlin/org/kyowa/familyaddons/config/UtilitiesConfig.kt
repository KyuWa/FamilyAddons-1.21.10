package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class UtilitiesConfig {
    @Expose @JvmField
    @ConfigOption(name = "Command Shortcuts", desc = "Enable short command aliases: /museum, /pw, /koff")
    @ConfigEditorBoolean
    var commandShortcuts = true

    @Expose @JvmField
    @ConfigOption(name = "Sign Math", desc = "Evaluate math expressions typed on signs before sending.")
    @ConfigEditorBoolean
    var signMath = true

    @Expose @JvmField
    @ConfigOption(name = "Item Prices", desc = "Show SkyBlock item prices in tooltips (AH, BIN, Bazaar, Pets).")
    @ConfigEditorBoolean
    var itemPrices = true

    @Expose @JvmField
    @ConfigOption(name = "Lock Hotbar Scroll", desc = "Prevent hotbar scroll from wrapping around (slot 1 won't go to slot 9 and vice versa).")
    @ConfigEditorBoolean
    var lockHotbarScroll = true

    @Expose @JvmField
    @ConfigOption(name = "Arachne Timer", desc = "Show a countdown timer hologram when an Arachne Crystal is placed.")
    @ConfigEditorBoolean
    var arachneTimer = true

    @Expose @JvmField
    @ConfigOption(name = "Highlight Rescan Interval", desc = "How often (in ticks) to scan for mobs to highlight. Lower = faster detection, higher = better performance. Default 20 (1 second).")
    @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
    var highlightRescanInterval = 20f
}