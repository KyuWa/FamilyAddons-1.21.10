package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class FarmCalcConfig {

    @Expose @JvmField
    @ConfigOption(name = "Foraging Wisdom", desc = "Your total Foraging Wisdom stat (from your profile). Used to show how much of your XP comes from wisdom bonuses.")
    @ConfigEditorText
    var foragingWisdom = "0"

    @Expose @JvmField
    @ConfigOption(name = "Taming Wisdom", desc = "Your total Taming Wisdom stat. Each point of Taming Wisdom gives +1% pet XP.")
    @ConfigEditorText
    var tamingWisdom = "0"

    @Expose @JvmField
    @ConfigOption(name = "Tree Gift Bonus XP (avg)", desc = "Average Foraging XP you get per Tree Gift reward. Check your gift hover text to find this number.")
    @ConfigEditorText
    var treeGiftAvgXp = "0"

    @Expose @JvmField
    @ConfigOption(name = "XP Per Hour (manual override)", desc = "Manual XP/hr in millions. Only used if the tracker has no data yet. Leave blank to always use tracker.")
    @ConfigEditorText
    var xpPerHour = ""

    @Expose @JvmField
    @ConfigOption(name = "Slot 1 Pet", desc = "Name of the pet in exp share slot 1. E.g. Tiger, Elephant, Bee")
    @ConfigEditorText
    var slotPet1 = ""

    @Expose @JvmField
    @ConfigOption(name = "Slot 2 Pet", desc = "Name of the pet in exp share slot 2.")
    @ConfigEditorText
    var slotPet2 = ""

    @Expose @JvmField
    @ConfigOption(name = "Slot 3 Pet", desc = "Name of the pet in exp share slot 3.")
    @ConfigEditorText
    var slotPet3 = ""
}
