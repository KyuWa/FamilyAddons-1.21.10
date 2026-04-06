package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class KuudraConfig {
    @Expose @JvmField
    @ConfigOption(name = "Auto Requeue", desc = "Auto requeue for Kuudra after each run.")
    @ConfigEditorBoolean
    var autoRequeue = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Basic", desc = "Auto requeue for Basic tier")
    @ConfigEditorBoolean
    var requeueBasic = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Hot", desc = "Auto requeue for Hot tier")
    @ConfigEditorBoolean
    var requeueHot = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Burning", desc = "Auto requeue for Burning tier")
    @ConfigEditorBoolean
    var requeueBurning = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Fiery", desc = "Auto requeue for Fiery tier")
    @ConfigEditorBoolean
    var requeueFiery = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Infernal", desc = "Auto requeue for Infernal tier")
    @ConfigEditorBoolean
    var requeueInfernal = true

    // DT Title accordion
    @Expose @JvmField
    @ConfigOption(name = "DT Title", desc = "")
    @ConfigEditorAccordion(id = 1)
    var dtTitleAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Enable DT Title", desc = "Show a fading title when someone requests DT in party chat.")
    @ConfigEditorBoolean
    var dtTitle = true

    @Expose @JvmField var dtTitleHudX = -1
    @Expose @JvmField var dtTitleHudY = -1
    @Expose @JvmField var dtTitleScale = "1.0"

    // Key Tracker accordion
    @Expose @JvmField
    @ConfigOption(name = "Key Tracker", desc = "")
    @ConfigEditorAccordion(id = 2)
    var keyTrackerAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 2)
    @ConfigOption(name = "Enable Key Tracker", desc = "Show key material counts in Mage/Barbarian shop.")
    @ConfigEditorBoolean
    var keyTracker = true

    @Expose @JvmField
    @ConfigOption(name = "Requeue Delay", desc = "Seconds to wait before requeuing after Kuudra ends.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 10f, minStep = 1f)
    var requeueDelaySecs = 0f

    @Expose @JvmField var keyTrackerHudX = 10
    @Expose @JvmField var keyTrackerHudY = 10
}
