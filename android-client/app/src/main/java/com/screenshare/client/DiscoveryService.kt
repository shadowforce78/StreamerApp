package com.screenshare.client

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  DiscoveryService — Service de découverte des serveurs sur le réseau local
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Ce service écoute en UDP sur le port BROADCAST_PORT (8888) pour recevoir
 * les messages de type "Phare" émis par le serveur PC.
 *
 * ⚠️  THREAD SAFETY :
 *   - Ce service tourne dans un Thread dédié (jamais sur le Main Thread).
 *   - Le callback `onServerFound` est invoqué depuis ce thread secondaire.
 *     L'Activity DOIT utiliser runOnUiThread() pour mettre à jour l'UI.
 *
 * Cycle de vie :
 *   1. startListening()  → Démarre le thread d'écoute
 *   2. onServerFound()   → Callback quand un serveur est détecté
 *   3. stopListening()   → Arrête le thread et ferme le socket
 */
class DiscoveryService(
    /** Callback invoqué quand un serveur est découvert (depuis un thread secondaire !) */
    private val onServerFound: (ServerInfo) -> Unit
) {
    companion object {
        private const val TAG = "DiscoveryService"

        /** Port de réception du broadcast UDP (doit correspondre au serveur) */
        private const val BROADCAST_PORT = 8888

        /** Taille max d'un paquet UDP (suffisant pour notre JSON) */
        private const val BUFFER_SIZE = 1024

        /** Timeout du socket en ms (permet de vérifier le flag `running` régulièrement) */
        private const val SOCKET_TIMEOUT_MS = 3000

        /** Identifiant attendu dans le JSON pour filtrer les paquets */
        private const val EXPECTED_APP_ID = "MonAppScreenShare"
    }

    // ── État interne ──
    // volatile garantit la visibilité entre threads
    @Volatile
    private var running = false

    // Le socket UDP d'écoute (nullable car initialisé dans le thread)
    private var socket: DatagramSocket? = null

    // Le thread d'écoute
    private var listenerThread: Thread? = null

    /**
     * Démarre l'écoute des broadcasts UDP sur le port 8888.
     *
     * ⚠️ Cette méthode crée un thread secondaire.
     *    NE PAS appeler depuis un thread réseau déjà existant.
     */
    fun startListening() {
        if (running) {
            Log.w(TAG, "Le service est déjà en cours d'exécution")
            return
        }

        running = true

        // ── Création du thread d'écoute ──
        // On utilise un Thread brut plutôt qu'une coroutine pour garder
        // un contrôle total sur le socket UDP et sa fermeture.
        listenerThread = Thread({
            Log.i(TAG, "📡 Démarrage de l'écoute sur le port $BROADCAST_PORT")

            var sock: DatagramSocket? = null
            try {
                // Création et configuration du socket UDP
                sock = DatagramSocket(BROADCAST_PORT)
                sock.broadcast = true
                // Le timeout permet au thread de vérifier `running` régulièrement
                // sans rester bloqué indéfiniment sur receive()
                sock.soTimeout = SOCKET_TIMEOUT_MS
                socket = sock

                val buffer = ByteArray(BUFFER_SIZE)

                // ── Boucle d'écoute principale ──
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)

                    try {
                        // Appel BLOQUANT (jusqu'au timeout)
                        // C'est pour ça qu'on est dans un thread séparé !
                        sock.receive(packet)

                        // Extraction des données du paquet
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val senderIp = packet.address.hostAddress ?: continue

                        Log.d(TAG, "📨 Paquet reçu de $senderIp : $message")

                        // Parsing du JSON et validation
                        val serverInfo = parseServerBroadcast(message, senderIp)
                        if (serverInfo != null) {
                            Log.i(TAG, "✅ Serveur découvert : ${serverInfo.name} (${serverInfo.ip})")
                            // ⚠️ Ce callback est invoqué depuis CE thread secondaire !
                            // L'Activity doit gérer le passage au Main Thread.
                            onServerFound(serverInfo)
                        }

                    } catch (e: SocketTimeoutException) {
                        // Timeout normal : le socket n'a rien reçu dans le délai imparti.
                        // On reboucle simplement pour vérifier si `running` est toujours true.
                        // Ce n'est PAS une erreur.
                    }
                }

            } catch (e: java.net.BindException) {
                // ── ERREUR CRITIQUE : Le port est déjà utilisé ──
                // Cela peut arriver si une autre instance de l'app tourne,
                // ou si un autre processus utilise le port 8888.
                Log.e(TAG, "❌ Port $BROADCAST_PORT déjà utilisé : ${e.message}")
            } catch (e: Exception) {
                if (running) {
                    // Erreur inattendue pendant l'écoute active
                    Log.e(TAG, "❌ Erreur dans le service de découverte : ${e.message}", e)
                }
                // Si !running, c'est probablement dû à la fermeture du socket
                // dans stopListening(), ce qui est normal.
            } finally {
                // ── Nettoyage garanti ──
                sock?.close()
                socket = null
                Log.i(TAG, "🛑 Service de découverte arrêté")
            }
        }, "DiscoveryThread") // Nom du thread pour le debug

        // isDaemon = true : le thread mourra automatiquement si l'app est détruite
        listenerThread?.isDaemon = true
        listenerThread?.start()
    }

    /**
     * Arrête proprement le service de découverte.
     *
     * La fermeture du socket provoque une SocketException dans receive(),
     * ce qui fait sortir le thread de sa boucle d'écoute.
     */
    fun stopListening() {
        Log.i(TAG, "Arrêt du service de découverte...")
        running = false

        // Fermer le socket force receive() à lancer une exception,
        // ce qui débloque le thread même s'il est en attente.
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lors de la fermeture du socket : ${e.message}")
        }

        // On attend la fin du thread (max 2 secondes)
        try {
            listenerThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interruption pendant l'attente du thread")
        }

        listenerThread = null
    }

    /**
     * Parse un message JSON de broadcast et crée un ServerInfo si valide.
     *
     * @param json Le contenu JSON du paquet UDP
     * @param senderIp L'adresse IP source du paquet (le serveur PC)
     * @return Un ServerInfo si le JSON est valide et correspond à notre app, null sinon
     */
    private fun parseServerBroadcast(json: String, senderIp: String): ServerInfo? {
        return try {
            val obj = JSONObject(json)

            // Vérification que c'est bien NOTRE application
            val app = obj.optString("app", "")
            if (app != EXPECTED_APP_ID) {
                Log.d(TAG, "Paquet ignoré (app='$app', attendu='$EXPECTED_APP_ID')")
                return null
            }

            ServerInfo(
                app = app,
                name = obj.optString("name", "Serveur inconnu"),
                ip = senderIp,  // L'IP est extraite du paquet, pas du JSON !
                commandPort = obj.optInt("command_port", 9999),
                videoPort = obj.optInt("video_port", 5000)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Erreur de parsing JSON : ${e.message}")
            null
        }
    }
}
