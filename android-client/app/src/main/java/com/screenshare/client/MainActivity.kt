package com.screenshare.client

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  MainActivity — Écran de découverte et de connexion au serveur
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Flux utilisateur :
 *   1. L'app démarre et lance le DiscoveryService en arrière-plan
 *   2. Quand un serveur est découvert, un bouton "Se connecter" apparaît
 *   3. Au clic, on envoie "START" en UDP et on ouvre le PlayerActivity
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
    }

    // ── Services ──
    private lateinit var discoveryService: DiscoveryService

    // ── État ──
    private var currentServer: ServerInfo? = null

    // ── Vues ──
    private lateinit var statusText: TextView
    private lateinit var serverNameText: TextView
    private lateinit var serverIpText: TextView
    private lateinit var connectButton: Button
    private lateinit var searchingProgress: ProgressBar
    private lateinit var serverCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Garder l'écran allumé (projecteur)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Liaison des vues ──
        statusText = findViewById(R.id.statusText)
        serverNameText = findViewById(R.id.serverNameText)
        serverIpText = findViewById(R.id.serverIpText)
        connectButton = findViewById(R.id.connectButton)
        searchingProgress = findViewById(R.id.searchingProgress)
        serverCard = findViewById(R.id.serverCard)

        // ── État initial : recherche en cours ──
        showSearchingState()

        // ── Configuration du bouton de connexion ──
        connectButton.setOnClickListener {
            onConnectClicked()
        }

        // ── Initialisation du service de découverte ──
        // Le callback onServerFound est appelé depuis le DiscoveryThread !
        discoveryService = DiscoveryService { serverInfo ->
            // ⚠️ ON EST DANS UN THREAD SECONDAIRE ICI !
            // Toute modification de l'UI doit passer par runOnUiThread.
            runOnUiThread {
                onServerDiscovered(serverInfo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Relancer la découverte quand l'Activity revient au premier plan
        // (ex: retour depuis le PlayerActivity après un STOP)
        discoveryService.startListening()
        Log.i(TAG, "📡 Découverte relancée")
    }

    override fun onPause() {
        super.onPause()
        // Arrêter la découverte quand l'Activity passe en arrière-plan
        // pour libérer le port UDP et économiser la batterie
        discoveryService.stopListening()
        Log.i(TAG, "⏸️ Découverte en pause")
    }

    /**
     * Appelé quand le DiscoveryService trouve un serveur.
     * ⚠️ DOIT être appelé sur le Main Thread (via runOnUiThread).
     */
    private fun onServerDiscovered(server: ServerInfo) {
        Log.i(TAG, "🖥️ Serveur découvert : ${server.name} @ ${server.ip}")

        currentServer = server
        showServerFoundState(server)
    }

    /**
     * Appelé quand l'utilisateur clique sur "Se connecter".
     * Envoie la commande START et ouvre le lecteur vidéo.
     */
    private fun onConnectClicked() {
        val server = currentServer ?: run {
            Toast.makeText(this, "Aucun serveur détecté", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "🔗 Connexion à ${server.name} (${server.ip})...")
        connectButton.isEnabled = false
        connectButton.text = "Connexion..."

        // ── Envoi de START dans un thread séparé ──
        CommandSender.sendStart(server) { success, message ->
            // ⚠️ Callback depuis un thread secondaire !
            runOnUiThread {
                if (success) {
                    Log.i(TAG, "✅ START envoyé, ouverture du lecteur vidéo")

                    // Ouvrir l'activité de lecture vidéo
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_SERVER_IP, server.ip)
                        putExtra(PlayerActivity.EXTRA_VIDEO_PORT, server.videoPort)
                        putExtra(PlayerActivity.EXTRA_COMMAND_PORT, server.commandPort)
                        putExtra(PlayerActivity.EXTRA_SERVER_NAME, server.name)
                    }
                    startActivity(intent)

                    // Remettre le bouton dans l'état initial
                    connectButton.isEnabled = true
                    connectButton.text = "Se connecter"
                } else {
                    Log.e(TAG, "❌ Échec de l'envoi de START : $message")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    connectButton.isEnabled = true
                    connectButton.text = "Se connecter"
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Gestion des états d'UI
    // ══════════════════════════════════════════════════════════════════

    /** Affiche l'état "Recherche en cours..." */
    private fun showSearchingState() {
        statusText.text = "Recherche d'un serveur sur le réseau..."
        searchingProgress.visibility = View.VISIBLE
        serverCard.visibility = View.GONE
    }

    /** Affiche l'état "Serveur trouvé" avec les infos */
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
