package org.kyowa.familyaddons.config

import com.google.gson.GsonBuilder
import io.github.notenoughupdates.moulconfig.common.IMinecraft
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor
import io.github.notenoughupdates.moulconfig.processor.BuiltinMoulConfigGuis
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor
import org.kyowa.familyaddons.Whitelist
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FamilyConfigManager {

    private val gson = GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .setPrettyPrinting()
        .create()

    private val configFile get() = File(net.minecraft.client.MinecraftClient.getInstance().runDirectory, "config/familyaddons/config.json")

    private var _config: FamilyConfig = FamilyConfig()
    val config: FamilyConfig get() = _config

    private lateinit var processor: MoulConfigProcessor<FamilyConfig>
    private lateinit var driver: ConfigProcessorDriver
    private lateinit var editor: MoulConfigEditor<FamilyConfig>
    private var editorInitialized = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun load() {
        configFile.parentFile.mkdirs()

        // 1. Run whitelist check FIRST so we know which config class to instantiate.
        val sessionUuid = try {
            net.minecraft.client.MinecraftClient.getInstance().session?.uuidOrNull
        } catch (e: Exception) {
            null
        }
        Whitelist.check(sessionUuid)

        // 2. Pick the right class. Whitelisted users get FamilyConfigPrivate (which
        //    has the @Category-annotated `hiddenForGui` field). Everyone else gets
        //    plain FamilyConfig (no Hidden category in the GUI).
        val configClass: Class<out FamilyConfig> = if (Whitelist.isAllowed()) {
            FamilyConfigPrivate::class.java
        } else {
            FamilyConfig::class.java
        }
        println("[FamilyAddons] Loading config as ${configClass.simpleName} (allowed=${Whitelist.isAllowed()})")

        // 3. Load the JSON into the chosen class.
        loadConfig(configClass)

        // 4. If we ended up with the private subclass, resync hiddenForGui.
        (_config as? FamilyConfigPrivate)?.resyncFromLoaded()

        // 5. Build the MoulConfig editor.
        processor = MoulConfigProcessor(_config)
        BuiltinMoulConfigGuis.addProcessors(processor)
        driver = ConfigProcessorDriver(processor)
        driver.processConfig(_config)

        scheduler.scheduleAtFixedRate({ save() }, 60, 60, TimeUnit.SECONDS)
    }

    /**
     * Re-runs the whitelist check with the player's actual UUID. Called from
     * FamilyAddons.kt on the first ClientPlayConnectionEvents.JOIN as a
     * defense-in-depth measure. If the result changes, the user gets a chat
     * message asking them to restart.
     */
    fun recheckWhitelist(uuid: java.util.UUID?) {
        val before = Whitelist.isAllowed()
        Whitelist.check(uuid)
        val after = Whitelist.isAllowed()
        if (before != after) {
            net.minecraft.client.MinecraftClient.getInstance().player?.sendMessage(
                net.minecraft.text.Text.literal(
                    "§6[FA] §7Whitelist status changed (allowed=§e$after§7). Restart the game to see the updated config."
                ),
                false
            )
        }
    }

    private fun loadConfig(configClass: Class<out FamilyConfig>) {
        if (!configFile.exists()) {
            _config = configClass.getDeclaredConstructor().newInstance()
            save()
            return
        }
        try {
            FileReader(configFile).use { fr ->
                val loaded: FamilyConfig? = gson.fromJson(fr, configClass)
                _config = loaded ?: configClass.getDeclaredConstructor().newInstance()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _config = configClass.getDeclaredConstructor().newInstance()
        }
    }

    fun save() {
        try {
            configFile.parentFile.mkdirs()
            FileWriter(configFile).use { fw -> fw.write(gson.toJson(_config)) }
            net.minecraft.client.MinecraftClient.getInstance().execute {
                org.kyowa.familyaddons.features.EntityHighlight.rescan()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEditor(): MoulConfigEditor<FamilyConfig> {
        if (!editorInitialized) {
            editor = MoulConfigEditor(processor)
            editorInitialized = true
        }
        return editor
    }

    fun openGui() {
        IMinecraft.getInstance().openWrappedScreen(getEditor())
    }
}