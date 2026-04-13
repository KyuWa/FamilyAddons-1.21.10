package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class BestiaryConfig {

    @Expose @JvmField
    @ConfigOption(name = "Enable HUD", desc = "Show the Bestiary tracker HUD on screen.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Display Mode", desc = "Total: all-time kills for this mob. Session: kills + uptime this session.")
    @ConfigEditorDropdown(values = ["Total", "Session"])
    var displayMode = 0  // 0 = Total, 1 = Session

    @Expose @JvmField
    @ConfigOption(name = "Mob Name", desc = "The mob you are tracking (e.g. 'Ghost'). HUD title will be '[Name] Bestiary'.")
    @ConfigEditorText
    var mobName = "Ghost"

    // ── Persisted total kills per mob name ────────────────────────────
    // Key = lowercased mob name, Value = accumulated total kills
    @Expose @JvmField
    var savedKills: MutableMap<String, Int> = mutableMapOf()

    // ── HUD position/scale (saved by HUD editor) ──────────────────────
    @Expose var hudX: Int = 10
    @Expose var hudY: Int = 10
    @Expose var hudScale: Float = 1.0f
}