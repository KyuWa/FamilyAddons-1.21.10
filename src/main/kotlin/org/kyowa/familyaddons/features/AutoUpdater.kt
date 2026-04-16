package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import org.kyowa.familyaddons.FamilyAddons
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

object AutoUpdater {

    private val GITHUB_REPO = if (FamilyAddons.MC_VERSION == "1.21.11")
        "KyuWa/FamilyAddons-1.21.11"
    else
        "KyuWa/FamilyAddons-1.21.10"

    private val http = HttpClient.newHttpClient()

    // Set once on launch, never changes until restart
    @Volatile var latestVersion: String? = null
        private set
    @Volatile var downloadUrl: String? = null
        private set
    @Volatile var updateAvailable: Boolean = false
        private set

    private var checked = false
    var downloading = false
        private set
    var downloaded = false
        private set

    // Called once from FamilyAddons.onInitializeClient()
    fun register() {
        if (checked) return
        checked = true
        CompletableFuture.runAsync { checkForUpdate() }

        // When title screen opens and update is available, inject the overlay screen
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is TitleScreen) return@register
            if (!updateAvailable) return@register
            if (downloaded) return@register  // already downloaded this session

            // Replace title screen with our update prompt overlay
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().setScreen(
                    UpdatePromptScreen(screen)
                )
            }
        }
    }

    private fun checkForUpdate() {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                .GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val tag = json.get("tag_name")?.asString?.trimStart('v') ?: return
            val assets = json.getAsJsonArray("assets") ?: return
            val asset = assets.firstOrNull {
                it.asJsonObject.get("name")?.asString?.endsWith(".jar") == true
            } ?: return

            latestVersion = tag
            downloadUrl = asset.asJsonObject.get("browser_download_url")?.asString
            updateAvailable = isNewer(tag, FamilyAddons.VERSION)

            if (updateAvailable) {
                FamilyAddons.LOGGER.info("AutoUpdater: update available — $tag (current: ${FamilyAddons.VERSION})")
            } else {
                FamilyAddons.LOGGER.info("AutoUpdater: up to date (${FamilyAddons.VERSION})")
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("AutoUpdater: check failed: ${e.message}")
        }
    }

    fun startDownload(onDone: (Boolean) -> Unit) {
        val url = downloadUrl ?: run { onDone(false); return }
        if (downloading) return
        downloading = true

        CompletableFuture.runAsync {
            try {
                val modsDir = File(MinecraftClient.getInstance().runDirectory, "mods")

                // Find and mark old jar for deletion
                val oldJars = modsDir.listFiles()?.filter {
                    it.name.startsWith("FamilyAddons") && it.name.endsWith(".jar")
                } ?: emptyList()

                // Download new jar
                val newName = "FamilyAddons-${latestVersion}-${FamilyAddons.MC_VERSION}.jar"
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                    .GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                val outFile = File(modsDir, newName)
                FileOutputStream(outFile).use { out -> resp.body().use { it.copyTo(out) } }

                // Delete old jars now that new one is safely downloaded
                oldJars.forEach { f ->
                    if (f.absolutePath != outFile.absolutePath) {
                        f.delete()
                        FamilyAddons.LOGGER.info("AutoUpdater: removed old jar ${f.name}")
                    }
                }

                downloaded = true
                FamilyAddons.LOGGER.info("AutoUpdater: downloaded $newName — restart to apply")
                MinecraftClient.getInstance().execute { onDone(true) }
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("AutoUpdater: download failed: ${e.message}")
                downloading = false
                MinecraftClient.getInstance().execute { onDone(false) }
            }
        }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        return try {
            val c = candidate.split(".").map { it.toInt() }
            val v = current.split(".").map { it.toInt() }
            for (i in 0 until maxOf(c.size, v.size)) {
                val ci = c.getOrElse(i) { 0 }
                val vi = v.getOrElse(i) { 0 }
                if (ci > vi) return true
                if (ci < vi) return false
            }
            false
        } catch (e: Exception) { false }
    }
}

// ── Update prompt overlay — shown on top of title screen ─────────────────
class UpdatePromptScreen(private val parent: Screen) : Screen(Text.literal("FamilyAddons Update")) {

    private var statusText: String? = null

    override fun init() {
        val centerX = width / 2
        val boxY = height / 2 - 50

        // Yes — download now
        addDrawableChild(
            ButtonWidget.builder(Text.literal("§aYes, update now")) {
                statusText = "§eDownloading..."
                AutoUpdater.startDownload { success ->
                    statusText = if (success)
                        "§aDownloaded! Restart Minecraft to apply."
                    else
                        "§cDownload failed. Check logs."
                }
            }
            .dimensions(centerX - 105, boxY + 70, 100, 20)
            .build()
        )

        // No — go back to title screen
        addDrawableChild(
            ButtonWidget.builder(Text.literal("§cNo, skip")) {
                MinecraftClient.getInstance().setScreen(parent)
            }
            .dimensions(centerX + 5, boxY + 70, 100, 20)
            .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Dim background
        context.fill(0, 0, width, height, 0xCC000000.toInt())

        val centerX = width / 2
        val boxY = height / 2 - 50
        val boxW = 280
        val boxH = 110
        val boxX = centerX - boxW / 2

        // Box background
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A2E.toInt())
        // Box border
        context.fill(boxX,         boxY,         boxX + boxW, boxY + 1,     0xFF6C63FF.toInt())
        context.fill(boxX,         boxY + boxH,  boxX + boxW, boxY + boxH + 1, 0xFF6C63FF.toInt())
        context.fill(boxX,         boxY,         boxX + 1,    boxY + boxH,  0xFF6C63FF.toInt())
        context.fill(boxX + boxW,  boxY,         boxX + boxW + 1, boxY + boxH, 0xFF6C63FF.toInt())

        val tr = textRenderer
        val latest = AutoUpdater.latestVersion ?: "?"
        val mcVer = FamilyAddons.MC_VERSION

        // Title
        val title = "§e§lFamilyAddons Update Available"
        context.drawText(tr, title, centerX - tr.getWidth(title.replace("§.", "")) / 2, boxY + 10, -1, true)

        // Version line
        val versionLine = "§fVersion §b$latest §7(Minecraft $mcVer)"
        context.drawText(tr, versionLine, centerX - tr.getWidth(versionLine.replace(Regex("§."), "")) / 2, boxY + 28, -1, true)

        // Question
        val question = "§7Would you like to update?"
        context.drawText(tr, question, centerX - tr.getWidth(question.replace(Regex("§."), "")) / 2, boxY + 44, -1, true)

        // Status text (downloading / done / error)
        val status = statusText
        if (status != null) {
            context.drawText(tr, status, centerX - tr.getWidth(status.replace(Regex("§."), "")) / 2, boxY + boxH + 8, -1, true)
        }

        super.render(context, mouseX, mouseY, delta)
    }

    // Prevent closing with Esc — force a choice
    override fun shouldCloseOnEsc() = false
}
