package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.common.text.StructuredText

/**
 * Public-tier FamilyConfig. Has all standard categories and a `hidden` field
 * that's saved to JSON (via @Expose) but does NOT have @Category, so the
 * MoulConfig GUI doesn't show a Hidden category for users of this class.
 *
 * For whitelisted users, [FamilyConfigPrivate] (which extends this) is
 * instantiated instead. It adds a second field `hiddenForGui` with @Category
 * that is synced to the same HiddenConfig instance as `hidden`, making the
 * Hidden category appear in the GUI for whitelisted users only.
 *
 * Two-class design avoids the runtime annotation manipulation which is
 * brittle across JDK versions (declaredAnnotations field is filtered in
 * JDK 12+, exact filtering varies between JDK builds).
 */
open class FamilyConfig : Config() {

    override fun getTitle(): StructuredText = StructuredText.of("§6FamilyAddons")

    @Expose @JvmField
    @Category(name = "General", desc = "General settings")
    var general = GeneralConfig()

    @Expose @JvmField
    @Category(name = "Chat Filters", desc = "Filter unwanted chat messages")
    var chatFilters = ChatFiltersConfig()

    @Expose @JvmField
    @Category(name = "Utilities", desc = "General utility features")
    var utilities = UtilitiesConfig()

    @Expose @JvmField
    @Category(name = "Crimson Isle", desc = "Crimson Isle features")
    var crimsonIsle = CrimsonIsleConfig()

    @Expose @JvmField
    @Category(name = "Party", desc = "Party management features")
    var party = PartyConfig()

    @Expose @JvmField
    @Category(name = "Mining", desc = "Mining features — Mineshaft & Pickaxe Ability")
    var mining = MiningConfig()

    @Expose @JvmField
    @Category(name = "Kuudra", desc = "Kuudra features")
    var kuudra = KuudraConfig()

    @Expose @JvmField
    @Category(name = "Solo Kuudra", desc = "Solo Kuudra features (Gorilla Tactics, Pearl Timer)")
    var soloKuudra = SoloKuudraConfig()

    @Expose @JvmField
    @Category(name = "Dungeons", desc = "Dungeon features")
    var dungeons = DungeonsConfig()

    @Expose @JvmField
    @Category(name = "Waypoints", desc = "Waypoint features")
    var waypoints = WaypointsConfig()

    @Expose @JvmField
    @Category(name = "World Scanner", desc = "Crystal Hollows structure scanner")
    var worldScanner = WorldScannerConfig()

    @Expose @JvmField
    @Category(name = "Parkour", desc = "Parkour system settings")
    var parkour = ParkourConfig()

    @Expose @JvmField
    @Category(name = "Highlight", desc = "ESP highlight for entities")
    var highlight = HighlightConfig()

    @Expose @JvmField
    @Category(name = "Keybinds", desc = "GFS keybinds for quick item restocking")
    var keybinds = KeybindsConfig()

    @Expose @JvmField
    @Category(name = "Bestiary", desc = "Bestiary kill tracker HUD")
    var bestiary = BestiaryConfig()

    @Expose @JvmField
    @Category(name = "Player Disguise", desc = "Replace player renders with a mob model")
    var playerDisguise = PlayerDisguiseConfig()

    @Expose @JvmField
    @Category(name = "Dev", desc = "Developer debug tools")
    var dev = DevConfig()

    @Expose @JvmField
    var hidden = HiddenConfig()
}