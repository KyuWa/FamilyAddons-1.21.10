package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class HighlightConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Highlight", desc = "Draw ESP around entities matching the list below.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Mob Names", desc = "Comma-separated list of mob names to highlight. Case insensitive.")
    @ConfigEditorText
    var mobNames = ""

    @Expose @JvmField
    @ConfigOption(name = "Color", desc = "Color of the ESP.")
    @ConfigEditorColour
    var color = "0:255:255:0:0"

    @Expose @JvmField
    @ConfigOption(name = "Drawing Style", desc = "ESP Box draws a wireframe box. Outline draws around the entity model.")
    @ConfigEditorDropdown(values = ["ESP Box", "Outline"])
    var drawingStyle = 0

    @Expose @JvmField
    @ConfigOption(name = "Tracer Lines", desc = "Draw lines from your crosshair to the nearest highlighted mobs.")
    @ConfigEditorBoolean
    var tracerEnabled = false

    @Expose @JvmField
    @ConfigOption(name = "Tracer Count", desc = "How many of the closest highlighted mobs to draw tracers to (1–20).")
    @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
    var tracerCount = 5f
}