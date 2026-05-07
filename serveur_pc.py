#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
 serveur_pc.py — Serveur de Screen Sharing Low-Latency (UDP / GStreamer)
=============================================================================

 Architecture :
   1. Thread "Phare"     → Broadcast UDP toutes les 2s sur le port 8888
   2. Thread Principal   → Écoute des commandes UDP sur le port 9999
   3. Sous-processus     → Pipeline GStreamer (capture → NVENC → RTP/UDP)

 Le serveur ne dépend que de la bibliothèque standard Python 3.
 Aucun framework externe n'est nécessaire.
=============================================================================
"""

import socket
import json
import time
import signal
import sys
import threading
import subprocess
import os

# ─────────────────────────── Configuration ────────────────────────────────
BROADCAST_PORT  = 8888   # Port du broadcast de découverte
COMMAND_PORT    = 9999   # Port d'écoute des commandes (START/STOP)
VIDEO_PORT      = 5000   # Port de sortie du flux RTP/UDP
BROADCAST_ADDR  = '255.255.255.255'
BROADCAST_INTERVAL = 2   # Intervalle en secondes entre chaque annonce

# Identité de ce serveur (personnalisable)
SERVER_NAME = "PC-Linux"

# ─────────────────────── Variables globales ────────────────────────────────
# Processus GStreamer en cours (None = pas de stream actif)
gst_process: subprocess.Popen | None = None
# Verrou pour manipuler gst_process de manière thread-safe
gst_lock = threading.Lock()
# Flag d'arrêt global
shutdown_event = threading.Event()


# ═══════════════════════════════════════════════════════════════════════════
#  1. LE PHARE — Broadcast UDP de découverte (multi-interface)
# ═══════════════════════════════════════════════════════════════════════════

def _get_broadcast_addresses():
    """
    Détecte toutes les adresses de broadcast de toutes les interfaces réseau.
    Fonctionne avec les interfaces LAN classiques ET les tunnels VPN (WireGuard).

    WireGuard (wg0) n'a pas d'adresse broadcast native car c'est du Layer 3
    point-à-point. On calcule le broadcast à partir de l'IP et du masque.

    Retourne une liste de tuples (interface_name, broadcast_address).
    """
    import ipaddress

    broadcasts = []

    try:
        # Utilise `ip -j addr show` pour obtenir les infos réseau en JSON
        result = subprocess.run(
            ["ip", "-j", "addr", "show"],
            capture_output=True, text=True, timeout=5
        )
        if result.returncode != 0:
            return [("fallback", BROADCAST_ADDR)]

        interfaces = json.loads(result.stdout)

        for iface in interfaces:
            name = iface.get("ifname", "?")
            # Ignorer loopback
            if name == "lo":
                continue

            for addr_info in iface.get("addr_info", []):
                if addr_info.get("family") != "inet":
                    continue  # IPv4 uniquement

                local_ip = addr_info.get("local", "")
                prefix_len = addr_info.get("prefixlen", 24)
                brd = addr_info.get("broadcast")

                if brd:
                    # L'interface a une adresse broadcast native (LAN classique)
                    broadcasts.append((name, brd))
                elif local_ip:
                    # Pas de broadcast (WireGuard, tunnel) → calculer depuis le subnet
                    try:
                        network = ipaddress.IPv4Network(
                            f"{local_ip}/{prefix_len}", strict=False
                        )
                        computed_brd = str(network.broadcast_address)
                        broadcasts.append((name, computed_brd))
                    except ValueError:
                        pass

    except Exception as e:
        print(f"[Phare] ⚠ Erreur détection interfaces : {e}")

    # Toujours inclure le broadcast global en fallback
    if not broadcasts:
        broadcasts.append(("fallback", BROADCAST_ADDR))

    return broadcasts


def start_broadcast():
    """
    Annonce la présence du serveur sur TOUTES les interfaces réseau.

    Envoie le JSON de découverte en UDP vers l'adresse broadcast de chaque
    interface détectée (LAN, WiFi, WireGuard, etc.).
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(BROADCAST_INTERVAL)

    message = json.dumps({
        "app": "MonAppScreenShare",
        "name": SERVER_NAME,
        "command_port": COMMAND_PORT,
        "video_port": VIDEO_PORT
    }).encode('utf-8')

    # Détection initiale des interfaces
    broadcast_targets = _get_broadcast_addresses()
    iface_list = ", ".join(f"{name}→{addr}" for name, addr in broadcast_targets)
    print(f"[Phare] 📡 Broadcast actif sur :{BROADCAST_PORT} (toutes les {BROADCAST_INTERVAL}s)")
    print(f"[Phare]    Interfaces : {iface_list}")

    refresh_counter = 0

    while not shutdown_event.is_set():
        # Rafraîchir la liste des interfaces toutes les 30 secondes
        # (utile si le VPN se connecte/déconnecte)
        refresh_counter += 1
        if refresh_counter >= 15:  # 15 * 2s = 30s
            broadcast_targets = _get_broadcast_addresses()
            refresh_counter = 0

        for iface_name, brd_addr in broadcast_targets:
            try:
                sock.sendto(message, (brd_addr, BROADCAST_PORT))
            except OSError as e:
                # Certaines interfaces peuvent être down → pas grave
                pass

        shutdown_event.wait(timeout=BROADCAST_INTERVAL)

    sock.close()
    print("[Phare] 🛑 Thread arrêté.")


# ═══════════════════════════════════════════════════════════════════════════
#  2. GESTION DU PIPELINE GSTREAMER
# ═══════════════════════════════════════════════════════════════════════════

def _detect_encoder():
    """
    Détecte l'encodeur H.264 matériel disponible.
    Priorité : NVENC → VA-API (AMD/Intel) → x264 (logiciel).
    Retourne un tuple (elements_list, encoder_name).
    """
    import shutil

    gst_inspect = shutil.which("gst-inspect-1.0")
    if not gst_inspect:
        return None, "gst-inspect-1.0 introuvable"

    # Tester si un élément GStreamer est instanciable
    def _element_works(element_name):
        try:
            result = subprocess.run(
                [gst_inspect, element_name],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=5
            )
            return result.returncode == 0
        except Exception:
            return False

    # ── Tentative 1 : NVENC (NVIDIA) ──
    if _element_works("nvh264enc"):
        return (
            ["nvh264enc", "preset=low-latency", "zerolatency=true"],
            "NVENC (NVIDIA)"
        )

    # ── Tentative 2 : VA-API (AMD / Intel) ──
    if _element_works("vah264enc"):
        return (
            # target-usage=7 : vitesse maximale (1=qualité, 7=vitesse)
            # rate-control=cqp : quantization constante (pas de buffering de bitrate)
            # ref-frames=1 : une seule frame de référence (moins de latence)
            # key-int-max=60 : keyframe toutes les 60 frames (2s à 30fps)
            # aud=false : pas de délimiteurs AU (économise quelques octets/frame)
            ["vah264enc", "target-usage=7", "rate-control=cqp", "ref-frames=1", "key-int-max=60", "aud=false"],
            "VA-API (AMD/Intel)"
        )

    # ── Tentative 3 : vaapih264enc (ancien plugin VA-API) ──
    if _element_works("vaapih264enc"):
        return (
            ["vaapih264enc", "tune=low-latency", "rate-control=cqp"],
            "VA-API legacy"
        )

    # ── Fallback : x264enc (encodage logiciel, plus lent) ──
    if _element_works("x264enc"):
        return (
            # tune=zerolatency : pas de buffering
            # speed-preset=ultrafast : encodage le plus rapide possible
            # bitrate=8000 : 8 Mbps (suffisant pour du 1080p60)
            ["x264enc", "tune=zerolatency", "speed-preset=ultrafast", "bitrate=8000", "key-int-max=30"],
            "x264 (logiciel — attention, CPU intensif)"
        )

    return None, "Aucun encodeur H.264 trouvé"


def start_gstreamer(client_ip: str):
    """
    Lance le pipeline GStreamer pour capturer l'écran, encoder en H.264
    et streamer en RTP/UDP vers le client.

    L'encodeur est auto-détecté :
      NVENC → VA-API → x264 (fallback logiciel)

    Pipeline :
      ximagesrc → raw caps (60fps) → videoconvert → <encoder> → rtph264pay → udpsink

    Args:
        client_ip: Adresse IP du client Android qui recevra le flux.
    """
    global gst_process

    with gst_lock:
        # Vérifier qu'il n'y a pas déjà un stream actif
        if gst_process is not None and gst_process.poll() is None:
            print(f"[GStreamer] ⚠ Stream déjà actif (PID {gst_process.pid}), arrêt avant redémarrage...")
            _kill_gstreamer_locked()

        # Détection de l'encodeur
        encoder_elements, encoder_name = _detect_encoder()
        if encoder_elements is None:
            print(f"[GStreamer] ❌ {encoder_name}")
            return

        print(f"[GStreamer] 🔍 Encodeur détecté : {encoder_name}")

        # ══════════════════════════════════════════════════════════════
        #  PIPELINE OPTIMISÉ POUR LA LATENCE MINIMALE
        # ══════════════════════════════════════════════════════════════
        #
        # Optimisations appliquées :
        #   1. videoscale → 1280x720 : réduit la charge GPU de ~75%
        #   2. 30fps : suffisant pour un film, divise le débit par 2
        #   3. queue max-size-buffers=1 : pas de buffering interne
        #   4. udpsink sync=false : envoie immédiatement sans attendre
        #
        cmd = [
            "gst-launch-1.0",
            # ── Capture d'écran ──
            "ximagesrc", "use-damage=false", "!",
            # ── Limiter le framerate AVANT tout traitement ──
            "video/x-raw,framerate=30/1", "!",
            # ── Conversion colorimétrique ──
            "videoconvert", "!",
            # ── Redimensionner en 720p (réduit la charge encodeur) ──
            "videoscale", "!",
            "video/x-raw,width=1280,height=720", "!",
        ] + encoder_elements + [
            # ── Queue minimale (1 seul buffer, pas de latence ajoutée) ──
            "!", "queue", "max-size-buffers=1", "max-size-bytes=0", "max-size-time=0", "!",
            # ── Empaquetage RTP avec envoi immédiat ──
            "rtph264pay", "config-interval=1", "pt=96", "!",
            "udpsink", f"host={client_ip}", f"port={VIDEO_PORT}", "sync=false"
        ]

        print(f"[GStreamer] 🚀 Lancement du pipeline → {client_ip}:{VIDEO_PORT}")
        print(f"[GStreamer]    Commande : {' '.join(cmd)}")

        try:
            # Lance GStreamer en sous-processus sans bloquer Python
            gst_process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,  # Fusionner stdout+stderr
                preexec_fn=os.setsid       # Nouveau groupe de processus (kill propre)
            )
            print(f"[GStreamer] ✅ Streaming actif (PID {gst_process.pid})")

            # Thread daemon pour afficher la sortie de GStreamer en temps réel
            def _read_gst_output(proc):
                try:
                    for line in iter(proc.stdout.readline, b''):
                        text = line.decode('utf-8', errors='replace').rstrip()
                        if text:
                            print(f"[GStreamer/out] {text}")
                except Exception:
                    pass
            threading.Thread(
                target=_read_gst_output,
                args=(gst_process,),
                daemon=True
            ).start()

        except FileNotFoundError:
            print("[GStreamer] ❌ ERREUR : gst-launch-1.0 introuvable !")
            print("[GStreamer]    Installez GStreamer : sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-bad")
            gst_process = None
        except Exception as e:
            print(f"[GStreamer] ❌ Erreur au lancement : {e}")
            gst_process = None


def stop_gstreamer():
    """Arrête proprement le pipeline GStreamer en cours."""
    global gst_process

    with gst_lock:
        _kill_gstreamer_locked()


def _kill_gstreamer_locked():
    """
    Arrête le processus GStreamer (doit être appelé avec gst_lock acquis).
    Escalade : SIGTERM → wait(3s) → SIGKILL si nécessaire.
    """
    global gst_process

    if gst_process is None:
        return

    if gst_process.poll() is not None:
        # Le processus est déjà terminé
        print(f"[GStreamer] ℹ Processus déjà terminé (code retour: {gst_process.returncode})")
        gst_process = None
        return

    pid = gst_process.pid
    print(f"[GStreamer] 🛑 Arrêt du stream (PID {pid})...")

    try:
        # Étape 1 : SIGTERM (arrêt gracieux)
        os.killpg(os.getpgid(pid), signal.SIGTERM)
        try:
            gst_process.wait(timeout=3)
            print("[GStreamer] ✅ Processus arrêté proprement.")
        except subprocess.TimeoutExpired:
            # Étape 2 : SIGKILL (arrêt forcé)
            print("[GStreamer] ⚠ Timeout, envoi SIGKILL...")
            os.killpg(os.getpgid(pid), signal.SIGKILL)
            gst_process.wait(timeout=2)
            print("[GStreamer] ✅ Processus tué.")
    except ProcessLookupError:
        print("[GStreamer] ℹ Processus déjà disparu.")
    except Exception as e:
        print(f"[GStreamer] ⚠ Erreur lors de l'arrêt : {e}")

    gst_process = None


# ═══════════════════════════════════════════════════════════════════════════
#  3. CENTRE DE CONTRÔLE — Écoute des commandes UDP
# ═══════════════════════════════════════════════════════════════════════════
def listen_for_commands():
    """
    Écoute les commandes UDP entrantes sur COMMAND_PORT.

    Commandes reconnues :
      - "START" : Lance le streaming GStreamer vers l'IP de l'expéditeur
      - "STOP"  : Arrête le streaming en cours

    L'adresse IP du client est extraite dynamiquement du paquet UDP reçu.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(1.0)  # Timeout pour vérifier le shutdown_event

    try:
        sock.bind(('0.0.0.0', COMMAND_PORT))
    except OSError as e:
        print(f"[Commandes] ❌ Impossible de bind sur le port {COMMAND_PORT} : {e}")
        print(f"[Commandes]    Un autre processus utilise peut-être ce port.")
        shutdown_event.set()
        return

    print(f"[Commandes] 👂 En écoute sur 0.0.0.0:{COMMAND_PORT}")
    print(f"[Commandes]    Commandes acceptées : START, STOP")
    print()

    while not shutdown_event.is_set():
        try:
            data, addr = sock.recvfrom(1024)
        except socket.timeout:
            # Timeout normal, on reboucle pour vérifier shutdown_event
            continue
        except OSError as e:
            if not shutdown_event.is_set():
                print(f"[Commandes] ⚠ Erreur réseau : {e}")
            continue

        # Décodage sécurisé du message
        try:
            command = data.decode('utf-8').strip().upper()
        except UnicodeDecodeError:
            print(f"[Commandes] ⚠ Paquet ignoré (encodage invalide) depuis {addr[0]}")
            continue

        # Extraction de l'IP de l'expéditeur (le client Android)
        client_ip = addr[0]
        client_port = addr[1]

        print(f"[Commandes] 📨 Reçu \"{command}\" depuis {client_ip}:{client_port}")

        # ── Traitement des commandes ──
        if command == "START":
            start_gstreamer(client_ip)

        elif command == "STOP":
            stop_gstreamer()

        else:
            print(f"[Commandes] ❓ Commande inconnue ignorée : \"{command}\"")

    sock.close()
    print("[Commandes] 🛑 Thread d'écoute arrêté.")


# ═══════════════════════════════════════════════════════════════════════════
#  4. POINT D'ENTRÉE — Gestion du cycle de vie
# ═══════════════════════════════════════════════════════════════════════════
def signal_handler(signum, frame):
    """Gestion propre de SIGINT (Ctrl+C) et SIGTERM."""
    print(f"\n[Main] 🛑 Signal {signum} reçu, arrêt en cours...")
    shutdown_event.set()
    stop_gstreamer()


def print_banner():
    """Affiche la bannière de démarrage."""
    print("=" * 60)
    print("  📺  Screen Share Server — Low Latency (UDP/NVENC)")
    print("=" * 60)
    print(f"  Broadcast   : UDP :{BROADCAST_PORT} (toutes les {BROADCAST_INTERVAL}s)")
    print(f"  Commandes   : UDP :{COMMAND_PORT}")
    print(f"  Vidéo (RTP) : UDP :{VIDEO_PORT}")
    print(f"  Encodeur    : NVENC (H.264, low-latency)")
    print("=" * 60)
    print()


if __name__ == "__main__":
    # Gestion des signaux pour un arrêt propre
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    print_banner()

    # 1. Lancer le thread Phare (daemon : meurt avec le processus principal)
    broadcast_thread = threading.Thread(
        target=start_broadcast,
        name="BroadcastThread",
        daemon=True
    )
    broadcast_thread.start()

    # 2. Lancer l'écoute des commandes sur le thread principal
    listen_for_commands()

    # 3. Nettoyage final
    print("[Main] 👋 Arrêt complet du serveur.")
    sys.exit(0)
