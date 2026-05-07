package com.screenshare.client

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * ══════════════════════════════════════════════════════════════════════════ PlayerActivity —
 * Lecture du flux vidéo RTP/UDP en plein écran via libVLC
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ┌───────────────────────────────────────────────────────────────────────┐ │ POURQUOI UN FICHIER
 * SDP ? │ ├───────────────────────────────────────────────────────────────────────┤ │ │ │ Le
 * serveur utilise rtph264pay avec pt=96 (payload type dynamique). │ │ VLC ne peut pas deviner que
 * pt=96 = H.264 sans un SDP. │ │ L'URI rtp://@:5000 ne fonctionne PAS avec les types dynamiques. │
 * │ │ │ Solution : On génère un fichier SDP minimal qui décrit le flux : │ │ m=video 5000 RTP/AVP
 * 96 │ │ a=rtpmap:96 H264/90000 │ │ │ │ VLC lit ce SDP, comprend que pt=96 = H.264 à 90kHz, et sait
 * │ │ comment dépaquetiser et décoder le flux RTP entrant. │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * ⚠️ THREAD SAFETY :
 * - SurfaceHolder.Callback : la lecture démarre dans surfaceCreated()
 * - Les événements VLC arrivent depuis un thread interne VLC
 * - L'envoi de STOP se fait dans un thread éphémère (CommandSender)
 * - Toute modification de l'UI passe par runOnUiThread{}
 */
class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "PlayerActivity"

        // Clés des extras passés via Intent
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_COMMAND_PORT = "command_port"
        const val EXTRA_SERVER_NAME = "server_name"
    }

    // ── libVLC ──
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    // ── UI ──
    private lateinit var videoSurface: SurfaceView
    private lateinit var disconnectButton: ImageButton
    private lateinit var statusOverlay: TextView

    // ── Informations du serveur ──
    private var serverIp: String = ""
    private var videoPort: Int = 5000
    private var commandPort: Int = 9999
    private var serverName: String = ""

    // ── État ──
    @Volatile private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // ── Plein écran immersif ──
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // ── Récupération des paramètres ──
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, 5000)
        commandPort = intent.getIntExtra(EXTRA_COMMAND_PORT, 9999)
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Serveur"

        if (serverIp.isEmpty()) {
            Toast.makeText(this, "Erreur : IP du serveur manquante", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ── Liaison des vues ──
        videoSurface = findViewById(R.id.videoSurface)
        disconnectButton = findViewById(R.id.disconnectButton)
        statusOverlay = findViewById(R.id.statusOverlay)

        disconnectButton.setOnClickListener { onDisconnectClicked() }

        statusOverlay.text = "Connexion au flux vidéo..."
        statusOverlay.visibility = View.VISIBLE

        // Étape 1 : Créer libVLC (sans attacher la surface)
        initializeLibVLC()

        // Étape 2 : Attendre que la surface soit prête
        videoSurface.holder.addCallback(this)
    }

    /**
     * Crée l'instance libVLC et le MediaPlayer avec les options optimisées pour la basse latence.
     */
    private fun initializeLibVLC() {
        Log.i(TAG, "🎬 Initialisation de libVLC")

        val vlcOptions =
                arrayListOf(
                        // ══════════════════════════════════════════════════════
                        //  CACHING = 150 → Le "Sweet Spot" pour MediaCodec + UDP
                        // ══════════════════════════════════════════════════════
                        // Laisse juste le temps d'assembler les fragments UDP d'une frame
                        "--network-caching=150",
                        "--live-caching=150",
                        "--file-caching=150",
                        "--sout-mux-caching=150",

                        // ── Pas de synchro horloge stricte ──
                        "--clock-jitter=0",
                        "--clock-synchro=0",

                        // ══════════════════════════════════════════════════════
                        //  DÉCODAGE RAPIDE — Excellent choix ici !
                        // ══════════════════════════════════════════════════════
                        // Désactiver le filtre de déblocking (gros gain CPU)
                        "--avcodec-skiploopfilter=4",
                        // Autoriser le drop de frames en retard (CRUCIAL)
                        "--drop-late-frames",
                        "--skip-frames",

                        // ── Pas d'audio (flux vidéo uniquement) ──
                        "--no-audio"
                )

        try {
            libVLC = LibVLC(this, vlcOptions)

            mediaPlayer =
                    MediaPlayer(libVLC).apply {
                        // ⚠️ Events arrivent depuis un thread VLC !
                        setEventListener { event ->
                            when (event.type) {
                                MediaPlayer.Event.Playing -> {
                                    Log.i(TAG, "▶️ Lecture en cours")
                                    runOnUiThread { statusOverlay.visibility = View.GONE }
                                }
                                MediaPlayer.Event.Buffering -> {
                                    val percent = event.buffering
                                    Log.d(TAG, "⏳ Buffering: ${percent}%")
                                    runOnUiThread {
                                        if (percent >= 99f) {
                                            // Buffering terminé → masquer l'overlay
                                            statusOverlay.visibility = View.GONE
                                        } else if (percent < 50f) {
                                            // Afficher uniquement quand le buffering est
                                            // significatif
                                            // (évite le flash "99%" pendant la lecture normale)
                                            statusOverlay.text = "Buffering ${percent.toInt()}%..."
                                            statusOverlay.visibility = View.VISIBLE
                                        }
                                    }
                                }
                                MediaPlayer.Event.EncounteredError -> {
                                    Log.e(TAG, "❌ Erreur de lecture VLC")
                                    runOnUiThread {
                                        statusOverlay.text = "Erreur de lecture vidéo"
                                        statusOverlay.visibility = View.VISIBLE
                                    }
                                }
                                MediaPlayer.Event.Stopped -> {
                                    Log.i(TAG, "⏹️ Lecture arrêtée")
                                }
                            }
                        }
                    }

            Log.i(TAG, "✅ libVLC et MediaPlayer créés")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur d'initialisation de libVLC : ${e.message}", e)
            runOnUiThread {
                statusOverlay.text = "Erreur d'initialisation du lecteur"
                statusOverlay.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Génère un fichier SDP décrivant le flux RTP H.264 entrant.
     *
     * ══════════════════════════════════════════════════════════════ POURQUOI C'EST NÉCESSAIRE
     * ══════════════════════════════════════════════════════════════
     *
     * Le serveur GStreamer utilise rtph264pay avec pt=96 (dynamique). Les payload types 96-127 sont
     * "dynamiques" dans le standard RTP, ce qui signifie que leur signification (quel codec ?)
     * n'est PAS définie par le numéro seul — il faut un SDP pour le spécifier.
     *
     * Le SDP contient la ligne cruciale : a=rtpmap:96 H264/90000 qui dit : "payload type 96 = codec
     * H.264, clock rate 90kHz"
     *
     * @return Le chemin absolu du fichier SDP généré
     */
    private fun generateSdpFile(): String {
        // ── Contenu SDP minimal mais complet ──
        // Chaque ligne est spécifiée par le RFC 4566 (SDP) et RFC 6184 (RTP H.264)
        val sdpContent =
                """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=ScreenShare
            c=IN IP4 0.0.0.0
            t=0 0
            m=video $videoPort RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=framerate:60
        """.trimIndent()

        // Écriture dans le cache de l'app (pas besoin de permissions)
        val sdpFile = File(cacheDir, "stream.sdp")
        sdpFile.writeText(sdpContent)

        Log.i(TAG, "📄 SDP généré : ${sdpFile.absolutePath}")
        Log.d(TAG, "📄 Contenu SDP :\n$sdpContent")

        return sdpFile.absolutePath
    }

    /**
     * Attache la surface vidéo à VLC et lance la lecture via le SDP.
     *
     * ⚠️ DOIT être appelé APRÈS surfaceCreated().
     */
    private fun startPlayback() {
        val mp = mediaPlayer ?: return
        val vlc = libVLC ?: return

        Log.i(TAG, "🎬 Attachement de la surface et démarrage de la lecture")

        // ── Attacher le VOut à la surface ──
        mp.vlcVout.setVideoSurface(videoSurface.holder.surface, videoSurface.holder)
        mp.vlcVout.setWindowSize(videoSurface.width, videoSurface.height)
        mp.vlcVout.attachViews()

        // ══════════════════════════════════════════════════════════
        //  GÉNÉRATION DU SDP ET LECTURE
        // ══════════════════════════════════════════════════════════
        val sdpPath = generateSdpFile()
        val sdpUri = Uri.fromFile(File(sdpPath))
        Log.i(TAG, "📺 Ouverture du SDP : $sdpUri")

        val media =
                Media(vlc, sdpUri).apply {
                    addOption(":network-caching=0")
                    addOption(":live-caching=0")
                    addOption(":clock-jitter=0")
                    addOption(":clock-synchro=0")
                }

        mp.media = media
        media.release() // libVLC fait une copie interne

        mp.play()
        Log.i(TAG, "✅ Lecture lancée via SDP, en attente du flux RTP sur le port $videoPort")
    }

    // ══════════════════════════════════════════════════════════════════
    //  SurfaceHolder.Callback
    // ══════════════════════════════════════════════════════════════════

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "🖥️ Surface créée — démarrage de la lecture")
        surfaceReady = true
        startPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "📐 Surface redimensionnée : ${width}x${height}")
        mediaPlayer?.vlcVout?.setWindowSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "🗑️ Surface détruite — détachement VLC")
        surfaceReady = false
        try {
            mediaPlayer?.vlcVout?.detachViews()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Erreur lors du détachement : ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Actions utilisateur
    // ══════════════════════════════════════════════════════════════════

    private fun onDisconnectClicked() {
        Log.i(TAG, "🔌 Déconnexion...")

        val server =
                ServerInfo(
                        app = "MonAppScreenShare",
                        name = serverName,
                        ip = serverIp,
                        commandPort = commandPort,
                        videoPort = videoPort
                )

        CommandSender.sendStop(server) { success, message ->
            runOnUiThread {
                if (success) {
                    Log.i(TAG, "✅ STOP envoyé")
                } else {
                    Log.w(TAG, "⚠️ Échec de l'envoi de STOP : $message")
                    Toast.makeText(this, "Impossible d'envoyer STOP", Toast.LENGTH_SHORT).show()
                }
            }
        }

        releasePlayer()
        finish()
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.let { mp ->
                mp.setEventListener(null)
                if (mp.isPlaying) {
                    mp.stop()
                }
                if (surfaceReady) {
                    mp.vlcVout.detachViews()
                }
                mp.release()
                Log.i(TAG, "🗑️ MediaPlayer libéré")
            }
            libVLC?.release()
            Log.i(TAG, "🗑️ libVLC libéré")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Erreur pendant la libération : ${e.message}")
        }
        mediaPlayer = null
        libVLC = null
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Cycle de vie Android
    // ══════════════════════════════════════════════════════════════════

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mediaPlayer?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        onDisconnectClicked()
    }
}
