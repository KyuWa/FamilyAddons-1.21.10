package org.kyowa.familyaddons.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import org.kyowa.familyaddons.FamilyAddons
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

object DiscordListener {

    private const val PORT       = 25570
    private const val CLAIM_PORT = 25571

    private val executor     = Executors.newCachedThreadPool()
    private val messageQueue = LinkedBlockingQueue<TicketInfo>()
    private val closeQueue   = LinkedBlockingQueue<CloseInfo>()
    private var serverSocket: ServerSocket? = null
    private var claimSocket:  ServerSocket? = null

    data class TicketInfo(
        val server:    String,
        val ign:       String,
        val tier:      String,
        val runs:      String,
        val channelId: String,
        val serverId:  String,
        val messageId: String,
    )

    data class CloseInfo(
        val server: String,
        val tier:   String,
        val ign:    String,
    )

    fun register() {
        executor.submit { runServer() }
        executor.submit { runClaimServer() }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (messageQueue.isNotEmpty()) {
                val ticket = messageQueue.poll() ?: break
                handleTicket(client, ticket)
            }
            while (closeQueue.isNotEmpty()) {
                val close = closeQueue.poll() ?: break
                handleClose(client, close)
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            serverSocket?.close()
            claimSocket?.close()
            executor.shutdown()
        }
    }

    // ── Inbound: bot → mod ────────────────────────────────────────────────────

    private fun runServer() {
        try {
            serverSocket = ServerSocket(PORT)
            FamilyAddons.LOGGER.info("[FA] Discord listener started on port $PORT")
            while (true) {
                val socket: Socket = serverSocket?.accept() ?: break
                handleConnection(socket)
            }
        } catch (e: Exception) {
            if (serverSocket?.isClosed == false)
                FamilyAddons.LOGGER.warn("[FA] Discord listener error: ${e.message}")
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return
                val json = JsonParser.parseString(line).asJsonObject

                when (json.get("action")?.asString) {
                    "ticket" -> {
                        val ticket = TicketInfo(
                            server    = json.get("server")?.asString     ?: "Unknown",
                            ign       = json.get("ign")?.asString        ?: return,
                            tier      = json.get("tier")?.asString       ?: "?",
                            runs      = json.get("runs")?.asString       ?: "?",
                            channelId = json.get("channel_id")?.asString ?: "",
                            serverId  = json.get("server_id")?.asString  ?: "",
                            messageId = json.get("message_id")?.asString ?: "",
                        )
                        messageQueue.offer(ticket)
                    }
                    "ticket_closed" -> {
                        val close = CloseInfo(
                            server = json.get("server")?.asString ?: "Unknown",
                            tier   = json.get("tier")?.asString   ?: "?",
                            ign    = json.get("ign")?.asString    ?: "?",
                        )
                        closeQueue.offer(close)
                    }
                }
            }
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("[FA] Discord listener parse error: ${e.message}")
        }
    }

    // ── Outbound: mod → bot (claim) ───────────────────────────────────────────

    private fun runClaimServer() {
        try {
            claimSocket = ServerSocket(CLAIM_PORT)
            FamilyAddons.LOGGER.info("[FA] Claim listener started on port $CLAIM_PORT")
            while (true) {
                val socket: Socket = claimSocket?.accept() ?: break
                socket.close()
            }
        } catch (e: Exception) {
            if (claimSocket?.isClosed == false)
                FamilyAddons.LOGGER.warn("[FA] Claim listener error: ${e.message}")
        }
    }

    fun sendClaim(channelId: String) {
        executor.submit {
            try {
                Socket("127.0.0.1", CLAIM_PORT).use { s ->
                    val json = JsonObject()
                    json.addProperty("action", "claim")
                    json.addProperty("channel_id", channelId)
                    PrintWriter(s.getOutputStream(), true).println(json.toString())
                }
                FamilyAddons.LOGGER.info("[FA] Sent claim for channel $channelId")
            } catch (e: Exception) {
                FamilyAddons.LOGGER.warn("[FA] Failed to send claim: ${e.message}")
            }
        }
    }

    // ── HUD messages ──────────────────────────────────────────────────────────

    private fun handleTicket(client: MinecraftClient, ticket: TicketInfo) {
        val player = client.player ?: return

        val tierColor = when (ticket.tier.lowercase()) {
            "infernal" -> "§c"
            "fiery"    -> "§6"
            "burning"  -> "§e"
            "hot"      -> "§a"
            "basic"    -> "§7"
            else       -> "§f"
        }

        val discordUrl = "https://discord.com/channels/${ticket.serverId}/${ticket.channelId}"
        val msg = Text.literal(
            "§5[Discord] §f${ticket.server} §8| §f${ticket.ign} §8| ${tierColor}${ticket.tier} §8x§f${ticket.runs}  "
        )

        // [Claim] — Skyblock Maniacs only
        if (ticket.server == "Skyblock Maniacs") {
            val channelId = ticket.channelId
            val claimBtn = Text.literal("§a§l§n[Claim]")
                .setStyle(
                    Style.EMPTY
                        .withClickEvent(ClickEvent.RunCommand("/faclaim $channelId"))
                        .withBold(true)
                        .withUnderline(true)
                )
            msg.append(claimBtn)
            msg.append(Text.literal("  "))
        }

        // [View Ticket] — always shown
        val viewTicket = Text.literal("§6§l§n[View Ticket]")
            .setStyle(
                Style.EMPTY
                    .withClickEvent(ClickEvent.OpenUrl(java.net.URI.create(discordUrl)))
                    .withBold(true)
                    .withUnderline(true)
            )
        msg.append(viewTicket)

        player.sendMessage(msg, false)
    }

    private fun handleClose(client: MinecraftClient, close: CloseInfo) {
        val player = client.player ?: return
        val tierColor = when (close.tier.lowercase()) {
            "infernal" -> "§c"
            "fiery"    -> "§6"
            "burning"  -> "§e"
            "hot"      -> "§a"
            "basic"    -> "§7"
            else       -> "§f"
        }
        player.sendMessage(
            Text.literal("§c[Discord] Ticket closed — §f${close.ign} §8| ${tierColor}${close.tier}"),
            false
        )
    }
}
