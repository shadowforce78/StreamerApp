package com.screenshare.client

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  CommandSender — Envoi de commandes UDP vers le serveur PC
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Envoie des commandes textuelles (START, STOP) au serveur via UDP Unicast.
 *
 * ⚠️  THREAD SAFETY :
 *   - Chaque envoi est effectué dans un thread éphémère.
 *   - Les opérations réseau sont INTERDITES sur le Main Thread d'Android
 *     (provoquent une NetworkOnMainThreadException).
 *   - Le callback `onResult` est invoqué depuis le thread d'envoi,
 *     donc l'appelant doit gérer le passage au Main Thread si besoin.
 *
 * Utilisation :
 *   CommandSender.send("START", serverInfo) { success, message ->
 *       runOnUiThread { updateUI(success, message) }
 *   }
 */
object CommandSender {
    private const val TAG = "CommandSender"

    /** Timeout d'envoi en millisecondes */
    private const val SEND_TIMEOUT_MS = 3000

    /**
     * Envoie une commande UDP au serveur.
     *
     * @param command  La commande à envoyer ("START" ou "STOP")
     * @param server   Les informations du serveur cible
     * @param onResult Callback (success: Boolean, message: String) — appelé depuis un thread secondaire !
     */
    fun send(
        command: String,
        server: ServerInfo,
        onResult: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        // ── Création d'un thread dédié pour l'envoi ──
        // On utilise un thread éphémère car l'envoi UDP est rapide (< 100ms)
        // et ne justifie pas un ThreadPool ou des coroutines.
        Thread({
            var socket: DatagramSocket? = null
            try {
                Log.i(TAG, "📤 Envoi de '$command' vers ${server.ip}:${server.commandPort}")

                // Préparation des données
                val data = command.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(server.ip)

                // Création du socket et envoi
                socket = DatagramSocket()
                socket.soTimeout = SEND_TIMEOUT_MS

                val packet = DatagramPacket(data, data.size, address, server.commandPort)
                socket.send(packet)

                Log.i(TAG, "✅ Commande '$command' envoyée avec succès")
                onResult?.invoke(true, "Commande '$command' envoyée")

            } catch (e: Exception) {
                // ── Gestion d'erreur robuste ──
                // Les erreurs réseau ne doivent JAMAIS crasher l'application.
                Log.e(TAG, "❌ Erreur d'envoi de '$command' : ${e.message}", e)
                onResult?.invoke(false, "Erreur : ${e.message}")
            } finally {
                // ── Fermeture systématique du socket ──
                socket?.close()
            }
        }, "CommandSender-$command").start()
    }

    /**
     * Envoie la commande "START" au serveur.
     * Raccourci pour send("START", server, onResult).
     */
    fun sendStart(server: ServerInfo, onResult: ((Boolean, String) -> Unit)? = null) {
        send("START", server, onResult)
    }

    /**
     * Envoie la commande "STOP" au serveur.
     * Raccourci pour send("STOP", server, onResult).
     */
    fun sendStop(server: ServerInfo, onResult: ((Boolean, String) -> Unit)? = null) {
        send("STOP", server, onResult)
    }
}
