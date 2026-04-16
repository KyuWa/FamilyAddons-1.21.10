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

    private val http = HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).build()

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
    var skipped = false
        private set

    fun register() {
        if (checked) return
        checked = true
        CompletableFuture.runAsync { checkForUpdate() }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is TitleScreen) return@register
            if (!updateAvailable) return@register
            if (downloaded) return@register
            if (AutoUpdater.skipped) return@register
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().setScreen(UpdatePromptScreen(screen))
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
                FamilyAddons.LOGGER.info("AutoUpdater: update available — $tag (you have ${FamilyAddons.VERSION})")
                FamilyAddons.LOGGER.info("AutoUpdater: download URL — $downloadUrl")
            } else {
                FamilyAddons.LOGGER.info("AutoUpdater: already up to date (${FamilyAddons.VERSION})")
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("AutoUpdater: check failed: ${e.message}")
        }
    }

    fun startDownload(onDone: (Boolean) -> Unit) {
        val url = downloadUrl ?: run { onDone(false); return }
        if (downloading) return
        downloading = true
        FamilyAddons.LOGGER.info("AutoUpdater: starting download of version $latestVersion")

        CompletableFuture.runAsync {
            try {
                val mc = MinecraftClient.getInstance()
                val modsDir = File(mc.runDirectory, "mods")
                val newName = "FamilyAddons-${latestVersion}.jar"

                // Download to a temp file first so we don't leave a broken jar if interrupted
                val tempFile = File(modsDir, "$newName.tmp")
                tempFile.delete() // clean up any previous failed attempt

                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "FamilyAddons/${FamilyAddons.VERSION}")
                    .GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                FileOutputStream(tempFile).use { out -> resp.body().use { it.copyTo(out) } }

                // Validate download — empty or tiny file means something went wrong
                if (!tempFile.exists() || tempFile.length() < 10_000) {
                    tempFile.delete()
                    throw Exception("Downloaded file is invalid (size: ${tempFile.length()} bytes)")
                }

                // Rename temp → final name
                val outFile = File(modsDir, newName)
                outFile.delete() // remove any previous partial download
                if (!tempFile.renameTo(outFile)) {
                    // renameTo can fail across drives — fall back to copy+delete
                    tempFile.copyTo(outFile, overwrite = true)
                    tempFile.delete()
                }
                FamilyAddons.LOGGER.info("AutoUpdater: downloaded $newName (${outFile.length() / 1024}KB)")

                // Find old jars to remove
                val oldJars = modsDir.listFiles()?.filter {
                    it.name.startsWith("FamilyAddons") &&
                            it.name.endsWith(".jar") &&
                            it.absolutePath != outFile.absolutePath
                } ?: emptyList()

                if (oldJars.isNotEmpty()) {
                    // Write a self-deleting Python-style cleanup using pure Java ProcessBuilder
                    // Same approach as SkyHanni: launch a separate JVM process to do deletions
                    // after MC exits, using the same java binary that's running MC right now
                    val isWindows = System.getProperty("os.name", "").startsWith("Windows")
                    val javaBin = System.getProperty("java.home") +
                            File.separator + "bin" + File.separator + "java" +
                            if (isWindows) ".exe" else ""

                    // Write a tiny inline Java source-less program as a jar isn't available,
                    // so instead write a platform script that the separate process runs
                    if (isWindows) {
                        val scriptFile = File(modsDir, "fa_update_cleanup.bat")
                        val sb = StringBuilder()
                        sb.appendLine("@echo off")
                        // Wait for MC process to release file handles
                        sb.appendLine(":waitloop")
                        for (f in oldJars) {
                            sb.appendLine("2>nul (>>\"${f.absolutePath}\" echo off) && goto :deletenow")
                        }
                        sb.appendLine("timeout /t 1 /nobreak >nul")
                        sb.appendLine("goto :waitloop")
                        sb.appendLine(":deletenow")
                        sb.appendLine("timeout /t 2 /nobreak >nul")
                        for (f in oldJars) {
                            sb.appendLine("del /f /q \"${f.absolutePath}\"")
                            sb.appendLine("if exist \"${f.absolutePath}\" del /f /q \"${f.absolutePath}\"")
                        }
                        sb.appendLine("del /f /q \"%~f0\"")
                        scriptFile.writeText(sb.toString())

                        Runtime.getRuntime().addShutdownHook(Thread {
                            try {
                                ProcessBuilder("cmd.exe", "/c", "start", "/min", "\"FA Cleanup\"", "/wait", "cmd.exe", "/c", scriptFile.absolutePath)
                                    .start()
                                FamilyAddons.LOGGER.info("AutoUpdater: cleanup script launched for ${oldJars.size} old jar(s)")
                            } catch (e: Exception) {
                                FamilyAddons.LOGGER.warn("AutoUpdater: failed to launch cleanup: ${e.message}")
                            }
                        })
                    } else {
                        // Linux/Mac: no file locking, just delete directly
                        oldJars.forEach { f ->
                            if (f.delete()) FamilyAddons.LOGGER.info("AutoUpdater: deleted ${f.name}")
                            else FamilyAddons.LOGGER.warn("AutoUpdater: could not delete ${f.name}")
                        }
                    }
                }

                downloaded = true
                downloading = false
                FamilyAddons.LOGGER.info("AutoUpdater: ready — restart Minecraft to apply update")
                mc.execute { onDone(true) }
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("AutoUpdater: download failed: ${e.message}")
                downloading = false
                MinecraftClient.getInstance().execute { onDone(false) }
            }
        }
    }

    fun skip() { skipped = true }

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

class UpdatePromptScreen(private val parent: Screen) : Screen(Text.literal("FamilyAddons Update")) {

    private var statusText: String? = null

    override fun init() {
        val centerX = width / 2
        val boxY = height / 2 - 50

        addDrawableChild(
            ButtonWidget.builder(Text.literal("§aYes, update now")) {
                if (AutoUpdater.downloading) return@builder
                statusText = "§eDownloading..."
                AutoUpdater.startDownload { success ->
                    if (success) {
                        MinecraftClient.getInstance().setScreen(parent)
                    } else {
                        statusText = "§cDownload failed — check logs. Click to retry."
                    }
                }
            }
                .dimensions(centerX - 105, boxY + 70, 100, 20)
                .build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal("§cNo, skip")) {
                AutoUpdater.skip()
                MinecraftClient.getInstance().setScreen(parent)
            }
                .dimensions(centerX + 5, boxY + 70, 100, 20)
                .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xCC000000.toInt())

        val centerX = width / 2
        val boxY = height / 2 - 50
        val boxW = 280
        val boxH = 110
        val boxX = centerX - boxW / 2

        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A2E.toInt())
        context.fill(boxX,        boxY,        boxX + boxW,     boxY + 1,        0xFF6C63FF.toInt())
        context.fill(boxX,        boxY + boxH, boxX + boxW,     boxY + boxH + 1, 0xFF6C63FF.toInt())
        context.fill(boxX,        boxY,        boxX + 1,        boxY + boxH,     0xFF6C63FF.toInt())
        context.fill(boxX + boxW, boxY,        boxX + boxW + 1, boxY + boxH,     0xFF6C63FF.toInt())

        val tr = textRenderer
        val latest = AutoUpdater.latestVersion ?: "?"

        val title = "§e§lFamilyAddons Update Available"
        context.drawText(tr, title, centerX - tr.getWidth(title.replace(Regex("§."), "")) / 2, boxY + 10, -1, true)

        val versionLine = "§fVersion §b$latest §7(MC ${FamilyAddons.MC_VERSION})"
        context.drawText(tr, versionLine, centerX - tr.getWidth(versionLine.replace(Regex("§."), "")) / 2, boxY + 28, -1, true)

        val question = "§7Would you like to update?"
        context.drawText(tr, question, centerX - tr.getWidth(question.replace(Regex("§."), "")) / 2, boxY + 44, -1, true)

        val status = statusText
        if (status != null) {
            context.drawText(tr, status, centerX - tr.getWidth(status.replace(Regex("§."), "")) / 2, boxY + boxH + 8, -1, true)
        }

        super.render(context, mouseX, mouseY, delta)
    }

    override fun shouldCloseOnEsc() = false
}