package org.kyowa.familyaddons.config

import io.github.notenoughupdates.moulconfig.annotations.Category

/**
 * Whitelisted FamilyConfig. Inherits everything from [FamilyConfig] and adds
 * a `hiddenForGui` field with @Category so MoulConfig registers the Hidden
 * GUI category for whitelisted users.
 *
 * `hiddenForGui` is initialized from `hidden` so they share the same
 * HiddenConfig instance — GUI mutations and feature code see the same data.
 *
 * NOT @Expose — gson never sees this field. Save/load goes through the
 * parent's `hidden` field exclusively, so JSON has only one `"hidden": {...}`
 * key (no duplicate `"hiddenForGui"` key).
 *
 * After gson loads the config, [FamilyConfigManager] calls [resyncFromLoaded]
 * to point `hiddenForGui` at the (possibly newly-allocated) `hidden` instance.
 */
class FamilyConfigPrivate : FamilyConfig() {

    @Category(name = "Hidden", desc = "Private features")
    @JvmField
    @Transient
    var hiddenForGui: HiddenConfig = hidden

    /**
     * Re-points `hiddenForGui` at the parent's current `hidden` reference.
     * Call this after gson deserialization, since gson may replace the
     * `hidden` field with a freshly-allocated instance.
     */
    fun resyncFromLoaded() {
        hiddenForGui = hidden
    }
}
