#!/usr/bin/env python3
"""
=============================================================================
ADMX - Remote Art-Net WebSocket Client Bridge
=============================================================================
Empfängt Art-Net 4 DMX-Datenströme hochgesichert über eine ausgehende WebSocket-
Verbindung vom Minecraft-Server (z. B. in Frankfurt) und strahlt die DMX-Pakete
lokal per UDP (Port 6454) an Lichtpulte (grandMA onPC, QLC+, Resolume, DMXControl)
oder USB-DMX-Interfaces (Enttec, DMXking) aus.

Vorteile:
- Funktioniert hinter jeder Heim-Firewall / Fritz!Box und bei DS-Lite / CGNAT
  (da ausgehende Verbindung).
- Ende-zu-Ende token-authentifiziert und TLS/WSS-fähig.
- Minimaler Overhead & Zero PII: Es werden ausschließlich rohe DMX-Bytes übertragen!

Installation:
    pip install websockets

Verwendung:
    python admx_artnet_client.py --server ws://meine-server-ip:8765 --token DEIN_TOKEN
"""

import sys
import time
import socket
import asyncio
import argparse

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

try:
    import websockets
except ImportError:
    print("\n[FEHLER] Das Modul 'websockets' ist nicht installiert.")
    print("Installiere es mit:")
    print("    pip install websockets\n")
    sys.exit(1)

ARTNET_PORT = 6454


class ArtNetBridgeClient:
    def __init__(self, server_url: str, token: str, udp_target: str, udp_port: int, broadcast: bool):
        self.server_url = server_url.rstrip("/")
        self.token = token
        self.udp_target = udp_target
        self.udp_port = udp_port
        self.broadcast = broadcast

        # UDP Socket vorbereiten
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if self.broadcast:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

        self.frames_received = 0
        self.last_fps_time = time.time()
        self.current_fps = 0.0

    async def run(self):
        ws_endpoint = f"{self.server_url}/artnet?token={self.token}"

        print("=" * 70)
        print("  ADMX - Professional Art-Net Remote WebSocket Bridge")
        print("=" * 70)
        print(f"  Server-Ziel : {self.server_url}")
        print(f"  Lokaler UDP : {self.udp_target}:{self.udp_port} (Broadcast: {self.broadcast})")
        print("=" * 70)

        backoff = 1.0

        while True:
            try:
                print(f"[VERBINDEN] Baue WebSocket-Verbindung auf...")
                async with websockets.connect(
                    ws_endpoint,
                    ping_interval=20,
                    ping_timeout=10,
                    max_size=65536
                ) as ws:
                    print("[OK] Verbunden mit ADMX Server! Art-Net Datenstrom aktiv.")
                    backoff = 1.0

                    async for message in ws:
                        if isinstance(message, bytes):
                            self.handle_artnet_packet(message)

            except websockets.exceptions.InvalidStatusCode as e:
                if e.status_code == 401:
                    print("[AUTH-FEHLER] Ungültiger Authentifizierungs-Token (401 Unauthorized)!")
                    print("Prüfe den Token auf dem Server mit: /admx artnet token")
                    await asyncio.sleep(5)
                elif e.status_code == 503:
                    print("[LIMIT-FEHLER] Maximale Anzahl an Art-Net Clients auf dem Server erreicht (503)!")
                    await asyncio.sleep(10)
                else:
                    print(f"[HTTP-FEHLER] Status-Code: {e.status_code}")
                    await asyncio.sleep(backoff)
            except Exception as e:
                print(f"[DISCONNECT] Verbindung unterbrochen: {e}")
                print(f"[RECONNECT] Neuer Versuch in {backoff:.1f} Sekunden...")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 1.5, 15.0)

    def handle_artnet_packet(self, packet: bytes):
        if len(packet) < 18 or not packet.startswith(b"Art-Net\x00"):
            return

        # An Ziel ausstrahlen (z.B. 127.0.0.1:6454 für grandMA / QLC+)
        try:
            self.sock.sendto(packet, (self.udp_target, self.udp_port))
        except Exception as e:
            print(f"[UDP-FEHLER] Konnte Paket nicht senden: {e}")
            return

        self.frames_received += 1
        now = time.time()
        if now - self.last_fps_time >= 1.0:
            self.current_fps = self.frames_received / (now - self.last_fps_time)
            self.frames_received = 0
            self.last_fps_time = now

            # Universum aus Header lesen
            sub_uni = packet[14]
            net = packet[15]
            universe = (net << 8) | sub_uni

            # Ersten Dimmer-Wert (Kanal 5 von Fixture 1) als Lebenszeichen auslesen
            sample_dimmer = packet[18 + 4] if len(packet) >= 23 else 0

            print(f"\r[STREAM] Uni: {universe:2d} | Rate: {self.current_fps:4.1f} FPS | Sample Dimmer Ch5: {sample_dimmer:3d} | Status: OK   ", end="", flush=True)


def parse_args():
    parser = argparse.ArgumentParser(description="ADMX Art-Net WebSocket-to-UDP Bridge")
    parser.add_argument("--server", default="ws://127.0.0.1:8765", help="WebSocket URL des Servers (z.B. ws://deine-ip:8765 oder wss://domain.de)")
    parser.add_argument("--token", required=True, help="Geheimer Authentifizierungs-Token (aus /admx artnet token)")
    parser.add_argument("--target", default="127.0.0.1", help="Lokales UDP-Ziel (Default: 127.0.0.1 für Software auf demselben PC)")
    parser.add_argument("--port", type=int, default=ARTNET_PORT, help=f"Lokaler UDP-Port (Default: {ARTNET_PORT})")
    parser.add_argument("--broadcast", action="store_true", help="Aktiviert UDP Broadcast (z.B. für physische Art-Net Nodes im Heimnetz)")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    client = ArtNetBridgeClient(
        server_url=args.server,
        token=args.token,
        udp_target=args.target,
        udp_port=args.port,
        broadcast=args.broadcast
    )
    try:
        asyncio.run(client.run())
    except KeyboardInterrupt:
        print("\n[STOP] Art-Net Bridge beendet.")
