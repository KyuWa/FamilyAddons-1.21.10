package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

class KuudraConfig {

    // ── Auto Requeue accordion (id=1) ─────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Auto Requeue", desc = "")
    @ConfigEditorAccordion(id = 1)
    var autoRequeueAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Auto Requeue", desc = "Auto requeue for Kuudra after each run.")
    @ConfigEditorBoolean
    var autoRequeue = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Basic", desc = "Auto requeue for Basic tier")
    @ConfigEditorBoolean
    var requeueBasic = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Hot", desc = "Auto requeue for Hot tier")
    @ConfigEditorBoolean
    var requeueHot = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Burning", desc = "Auto requeue for Burning tier")
    @ConfigEditorBoolean
    var requeueBurning = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Fiery", desc = "Auto requeue for Fiery tier")
    @ConfigEditorBoolean
    var requeueFiery = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Infernal", desc = "Auto requeue for Infernal tier")
    @ConfigEditorBoolean
    var requeueInfernal = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Check Party Size", desc = "Cancel requeue if party has fewer than 4 players.")
    @ConfigEditorBoolean
    var checkPartySize = false

    @Expose @JvmField
    @ConfigAccordionId(id = 1)
    @ConfigOption(name = "Requeue Delay", desc = "Seconds to wait before requeuing after Kuudra ends.")
    @ConfigEditorSlider(minValue = 0f, maxValue = 10f, minStep = 1f)
    var requeueDelaySecs = 0f

    // ── DT Title accordion (id=2) ─────────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "DT Title", desc = "")
    @ConfigEditorAccordion(id = 2)
    var dtTitleAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 2)
    @ConfigOption(name = "Enable DT Title", desc = "Show a fading centered title when someone requests DT in party chat.")
    @ConfigEditorBoolean
    var dtTitle = false

    @Expose @JvmField var dtTitleHudX = -1
    @Expose @JvmField var dtTitleHudY = -1
    @Expose @JvmField var dtTitleScale = "2.0"

    // ── Key Tracker accordion (id=3) ──────────────────────────
    @Expose @JvmField
    @ConfigOption(name = "Key Tracker", desc = "")
    @ConfigEditorAccordion(id = 3)
    var keyTrackerAccordion = false

    @Expose @JvmField
    @ConfigAccordionId(id = 3)
    @ConfigOption(name = "Enable Key Tracker", desc = "Show key material counts in Mage/Barbarian shop.")
    @ConfigEditorBoolean
    var keyTracker = false

    @Expose @JvmField var keyTrackerHudX = 10
    @Expose @JvmField var keyTrackerHudY = 10
}