package com.screenshare.client

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  MainActivity — Écran de découverte et de connexion au serveur
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Deux modes de connexion :
 *   1. AUTOMATIQUE : Le DiscoveryService écoute les broadcasts UDP (LAN)
 *   2. MANUELLE    : L'utilisateur saisit l'IP du serveur (VPN / WireGuard)
 *
 * ⚠️  ARCHITECTURE THREAD :
 *   - Main Thread    : UI uniquement (affichage, clics)
 *   - DiscoveryThread: Écoute UDP broadcast (port 8888)
 *   - CommandThread  : Envoi UDP éphémère (START/STOP)
 *
 *   Les callbacks du DiscoveryService arrivent depuis un thread secondaire.
 *   On utilise runOnUiThread{} pour toute modification de l'UI.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Ports par défaut (utilisés pour la connexion manuelle) */
        private const val DEFAULT_COMMAND_PORT = 9999
        private const val DEFAULT_VIDEO_PORT = 5000
    }

    // ── Services ──
    private lateinit var discoveryService: DiscoveryService

    // ── État ──
    private var currentServer: ServerInfo? = null

    // ── Vues : Découverte automatique ──
    private lateinit var statusText: TextView
    private lateinit var serverNameText: TextView
    private lateinit var serverIpText: TextView
    private lateinit var connectButton: Button
    private lateinit var searchingProgress: ProgressBar
    private lateinit var serverCard: View

    // ── Vues : Connexion manuelle ──
    private lateinit var manualIpInput: EditText
    private lateinit var manualConnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Garder l'écran allumé (projecteur)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Liaison des vues : Découverte automatique ──
        statusText = findViewById(R.id.statusText)
        serverNameText = findViewById(R.id.serverNameText)
        serverIpText = findViewById(R.id.serverIpText)
        connectButton = findViewById(R.id.connectButton)
        searchingProgress = findViewById(R.id.searchingProgress)
        serverCard = findViewById(R.id.serverCard)

        // ── Liaison des vues : Connexion manuelle ──
        manualIpInput = findViewById(R.id.manualIpInput)
        manualConnectButton = findViewById(R.id.manualConnectButton)

        // ── État initial : recherche en cours ──
        showSearchingState()

        // ── Bouton connexion automatique ──
        connectButton.setOnClickListener {
            onConnectClicked()
        }

        // ── Bouton connexion manuelle ──
        manualConnectButton.setOnClickListener {
            onManualConnectClicked()
        }

        // ── Touche "Done" sur le clavier → connexion manuelle ──
        manualIpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onManualConnectClicked()
                true
            } else false
        }

        // ── Initialisation du service de découverte ──
        // Le callback onServerFound est appelé depuis le DiscoveryThread !
        discoveryService = DiscoveryService { serverInfo ->
            // ⚠️ ON EST DANS UN THREAD SECONDAIRE ICI !
            runOnUiThread {
                onServerDiscovered(serverInfo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        discoveryService.startListening()
        Log.i(TAG, "📡 Découverte relancée")
    }

    override fun onPause() {
        super.onPause()
        discoveryService.stopListening()
        Log.i(TAG, "⏸️ Découverte en pause")
    }

    /**
     * Appelé quand le DiscoveryService trouve un serveur.
     * ⚠️ DOIT être appelé sur le Main Thread.
     */
    private fun onServerDiscovered(server: ServerInfo) {
        Log.i(TAG, "🖥️ Serveur découvert : ${server.name} @ ${server.ip}")
        currentServer = server
        showServerFoundState(server)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Connexion automatique (serveur découvert par broadcast)
    // ══════════════════════════════════════════════════════════════════

    private fun onConnectClicked() {
        val server = currentServer ?: run {
            Toast.makeText(this, "Aucun serveur détecté", Toast.LENGTH_SHORT).show()
            return
        }
        connectToServer(server)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Connexion manuelle (IP saisie par l'utilisateur)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Appelé quand l'utilisateur clique sur "Connexion manuelle".
     * Crée un ServerInfo à partir de l'IP saisie avec les ports par défaut.
     */
    private fun onManualConnectClicked() {
        val ip = manualIpInput.text.toString().trim()

        if (ip.isEmpty()) {
            Toast.makeText(this, "Entrez une adresse IP", Toast.LENGTH_SHORT).show()
            manualIpInput.requestFocus()
            return
        }

        // Validation basique de l'IP (IPv4)
        val ipRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!ipRegex.matches(ip)) {
            Toast.makeText(this, "Adresse IP invalide", Toast.LENGTH_SHORT).show()
            manualIpInput.requestFocus()
            return
        }

        Log.i(TAG, "🔒 Connexion manuelle vers $ip")

        // Construire un ServerInfo avec les ports par défaut
        val server = ServerInfo(
            app = "MonAppScreenShare",
            name = "Serveur ($ip)",
            ip = ip,
            commandPort = DEFAULT_COMMAND_PORT,
            videoPort = DEFAULT_VIDEO_PORT
        )

        connectToServer(server)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Logique commune de connexion
    // ══════════════════════════════════════════════════════════════════

    /**
     * Envoie START au serveur et ouvre le lecteur vidéo.
     * Utilisé par les deux modes de connexion (auto et manuel).
     */
    private fun connectToServer(server: ServerInfo) {
        Log.i(TAG, "🔗 Connexion à ${server.name} (${server.ip})...")

        // Désactiver les deux boutons pendant la connexion
        connectButton.isEnabled = false
        manualConnectButton.isEnabled = false
        manualConnectButton.text = "Connexion..."

        // ── Envoi de START dans un thread séparé ──
        CommandSender.sendStart(server) { success, message ->
            // ⚠️ Callback depuis un thread secondaire !
            runOnUiThread {
                if (success) {
                    Log.i(TAG, "✅ START envoyé, ouverture du lecteur vidéo")

                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_SERVER_IP, server.ip)
                        putExtra(PlayerActivity.EXTRA_VIDEO_PORT, server.videoPort)
                        putExtra(PlayerActivity.EXTRA_COMMAND_PORT, server.commandPort)
                        putExtra(PlayerActivity.EXTRA_SERVER_NAME, server.name)
                    }
                    startActivity(intent)
                } else {
                    Log.e(TAG, "❌ Échec de l'envoi de START : $message")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }

                // Remettre les boutons dans l'état initial
                connectButton.isEnabled = true
                connectButton.text = "Se connecter"
                manualConnectButton.isEnabled = true
                manualConnectButton.text = "Connexion manuelle"
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Gestion des états d'UI
    // ══════════════════════════════════════════════════════════════════

    private fun showSearchingState() {
        statusText.text = "Recherche d'un serveur sur le réseau..."
        searchingProgress.visibility = View.VISIBLE
        serverCard.visibility = View.GONE
    }

    private fun showServerFoundState(server: ServerInfo) {
        statusText.text = "Serveur détecté !"
        searchingProgress.visibility = View.GONE
        serverCard.visibility = View.VISIBLE
        serverNameText.text = server.name
        serverIpText.text = "${server.ip} — Ports: cmd=${server.commandPort}, vidéo=${server.videoPort}"
        connectButton.isEnabled = true
        connectButton.text = "Se connecter"
    }
}
