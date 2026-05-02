package com.screenshare.client

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  ServerInfo — Modèle de données pour un serveur découvert
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Représente les informations reçues via le broadcast UDP du serveur PC.
 * Ce data class est parsé à partir du JSON envoyé par le Phare.
 *
 * Exemple de JSON reçu :
 *   {"app":"MonAppScreenShare","name":"PC-Linux","command_port":9999,"video_port":5000}
 */
data class ServerInfo(
    /** Identifiant de l'application (pour filtrer les broadcasts parasites) */
    val app: String,

    /** Nom lisible du serveur (affiché dans l'UI) */
    val name: String,

    /** Adresse IP du serveur (extraite de l'adresse source du paquet UDP) */
    val ip: String,

    /** Port UDP sur lequel le serveur attend les commandes (START/STOP) */
    val commandPort: Int,

    /** Port UDP sur lequel le serveur envoie le flux vidéo RTP */
    val videoPort: Int
)
