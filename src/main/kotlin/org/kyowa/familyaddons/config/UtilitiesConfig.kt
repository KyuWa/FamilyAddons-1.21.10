package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
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
}
