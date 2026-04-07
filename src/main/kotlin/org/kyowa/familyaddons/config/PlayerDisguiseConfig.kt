package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class PlayerDisguiseConfig {

    @Expose @JvmField
    @ConfigOption(name = "Enabled", desc = "Replace player renders with the chosen mob model.")
    @ConfigEditorBoolean
    var enabled: Boolean = false

    @Expose @JvmField
    @ConfigOption(name = "Mob ID", desc = "Entity ID to disguise as, e.g. minecraft:slime, minecraft:creeper, minecraft:zombie")
    @ConfigEditorText
    var mobId: String = "minecraft:slime"

    @Expose @JvmField
    @ConfigOption(name = "Scope", desc = "Self Only = only you see yourself as the mob. Everyone = all players appear as the mob.")
    @ConfigEditorDropdown(values = ["Self Only", "Everyone"])
    var scope: Int = 0

    @Expose @JvmField
    @ConfigOption(name = "Baby", desc = "Render as a baby mob if the mob type supports it (e.g. zombie, cow, pig).")
    @ConfigEditorBoolean
    var baby: Boolean = false
}
