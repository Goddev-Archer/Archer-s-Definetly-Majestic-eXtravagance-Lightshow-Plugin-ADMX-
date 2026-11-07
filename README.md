# ADMX – Archer's Definitely Majestic eXtravagance Lightshow-Plugin 💡✨

[![Minecraft Paper](https://img.shields.io/badge/Minecraft-Paper%201.21%2B-brightgreen.svg)](https://papermc.io)
[![Java Version](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Zero Client Mods](https://img.shields.io/badge/Client%20Mods-0%20(100%25%20Vanilla)-success.svg)](#)

> **Professionelle Festival-, Konzert- und Club-Lichtshows in Minecraft – komplett mod-frei!**  
> ADMX nutzt moderne Minecraft `Display`- (`ItemDisplay`, `TextDisplay`, `BlockDisplay`) und `Interaction`-Entities, um 3D-Moving-Heads, volumetrische Lichtkegel, animierten Kalt- und Raumnebel sowie interaktive 3D-Hologramm-Pulte darzustellen – ohne Forge, Fabric oder clientseitige Modifikationen.

---

## 🌟 Highlights & Features

- 🔦 **Volumetrische 3D-Beams & Laser:**
  - Mathematisch präzise Lichtkegel (`SPOT_CONE`), flache 2D-Scanning-Fächer (`LASER_FAN`) und messerscharfe Nadelstrahlen (`LASER_NEEDLE`).
  - Flüssige, per-Tick interpolierte Pan/Tilt-Schwenks für Moving-Heads mit automatischer Linsen-Offset-Kompensation (keine Teleport-Lags).
  - Volle Farbdynamik mit wahrnehmungsgetreuer Farbmischung im **Oklab-Farbraum**.

- 🌫️ **Dynamischer Raum- & Bodennebel (Fluid Wave Mesh):**
  - Automatisches Raumvolumen-Scanning (Flood-Fill bis 30 m).
  - Animierte Dreiecks-Wellenoberfläche für Kaltnebel mit Kanten-Partikeln und Raum-Dunst (Hazer).
  - Physikalische Lichtbrechung und echter Sichtraub für maximale Atmosphäre.

- 🎛️ **Duale Kontroll-Interfaces:**
  - **In-World 3D-Holo-Konsole (`HoloConsoleUi`):** Schwebendes Regiepult mit berührungsempfindlichen Fadern, Knöpfen und Statusanzeigen direkt im 3D-Raum via Raycast-Interaktion.
  - **Chest-UI (`ChestConsoleUi`):** Traditionelles, strukturiertes Inventar-Interface für schnelle Steuerung, Farbpaletten, Fanning, Gobo-Simulation und Strobe-Tempo.

- 🎵 **Sound-to-Light & Live-Audio-Reaktivität:**
  - Integrierter DSP-Spektrumanalysator mit Fast-Fourier-Transformation (FFT) und automatischer Beat-/Drop-Erkennung.
  - Live-Streaming von YouTube, Spotify, DJ-Software (Rekordbox, Serato, Traktor) via Python WASAPI Loopback (`tools/audio-streamer`).

- 🌐 **Remote Art-Net 4 & WebSocket-Bridge:**
  - Anbindung an externe Lichtpulte und VJ-Software (**grandMA3 onPC**, **QLC+**, **Resolume Arena**, **Madrix**, **DMXControl**).
  - Outbound WebSocket-Bridge (`tools/artnet-bridge`) – funktioniert weltweit ohne Port-Forwarding, ohne Router-Freigaben und hinter IPv6 / DS-Lite.

- 🧩 **Visuelles Flowboard-System:**
  - Node-basiertes Event- und Logik-Routing für Automatisierungen, Cue-Chains und synchronisierte Showabläufe.

- 🛡️ **Sicherheit & Performance by Design:**
  - **Photosensitivitäts-Schutz (`/admx safe`):** Spieler können Flacker- und Strobe-Effekte für ihren Client per Mausklick deaktivieren.
  - Capped Strobe-Frequenzen (≤ 10 Hz) und konfigurierbare Server-Budgets.
  - Asynchrone, debouncte SQLite-Persistenz ohne Main-Thread-Blocking.
  - Intelligentes LOD-Culling und automatische Entity-Hygiene.

---

##  Geräteübersicht (Fixtures)

| Gerätetyp | Kategorie | Strahl-Modus | Beschreibung |
| :--- | :--- | :--- | :--- |
| **Moving Head Spotlight** | Moving Head | `SPOT_CONE` | 3-teiliger Kopf mit volumetrischem Spot-Kegel für Sweeps & Bühnenakzente |
| **Moving Head Beam (Sharpie)** | Moving Head | `LASER_NEEDLE` | Enger, extrem intensiver Nadelstrahl mit hoher Reichweite |
| **Moving Head Wash** | Moving Head | `SPOT_CONE` | Weitwinkliger Fluter mit Wabenlinsen-Cluster zur Flächenbeleuchtung |
| **Moving Laser Head** | Moving Head | `LASER_FAN` | 360° schwenkbarer Showlaser für dynamische Fächer-Projektionen |
| **Show-Laser (Scanning Fan)** | Statisch | `LASER_FAN` | Fächerförmiger Club-Laser mit Scannereffekt |
| **Show-Laser (Needle Beam)** | Statisch | `LASER_NEEDLE` | Präziser Nadel-Showlaser |
| **Audience Blinder (2-Lite)** | Statisch | `SPOT_CONE` | Klassischer 2-fach Warmweiß-Publikumsblinder |
| **LED Strobe / Sunstrip** | Statisch | `SPOT_CONE` | Mehrsegment-LED-Leiste für Stroboskop-Gewitter und Lauflichter |
| **Raumnebelmaschine (Hazer)** | Atmosphäre | Raum-Mesh | Füllt Räume bis 30 m mit feinem Dunst zur Brechung von Lichtstrahlen |
| **Kaltnebelmaschine (Low Fog)** | Atmosphäre | Boden-Mesh | Erzeugt schweren Bodennebel mit wabernden Wellenbewegungen |
| **DMX Flammenprojektor** | Pyro | Partikel / FX | Vertikale Flammenstöße mit DMX-Trigger |

---

## 📋 Befehle & Berechtigungen

### Befehle

| Befehl | Berechtigung | Beschreibung |
| :--- | :--- | :--- |
| `/admx wand` | `admx.place` | Gibt den ADMX Placement- & Kontrollstab (Wand) |
| `/admx spawn <typ> [gruppe]` | `admx.place` | Platziert ein Gerät am aktuellen Standort |
| `/admx ui [chest\|holo\|open\|close]` | `admx.console` | Öffnet oder wechselt zwischen Chest-UI und 3D-Hologramm-Pult |
| `/admx s2l` | `admx.control` | Öffnet das Sound-to-Light Hardware-Steuerpult |
| `/admx artnet [status\|token\|start\|stop]` | `admx.admin` | Verwaltet die Art-Net WebSocket-Bridge |
| `/admx flow [boardId]` | `admx.control` | Öffnet das visuelle Flowboard-Programmierpult |
| `/admx auto [on\|off\|s2l\|status]` | `admx.control` | Steuert die automatische Show- und Musik-Synchronisation |
| `/admx group [list\|select\|create\|delete] <id>` | `admx.control` | Verwaltet DMX-Gerätegruppen |
| `/admx scene [play\|stop\|rec\|load] [1-4]` | `admx.control` | Szenen- und Sequencer-Steuerung |
| `/admx safe` | `admx.safe` | Schaltet den persönlichen Photosensitivitäts-Schutzmodus um |
| `/admx purge` | `admx.admin` | Bereinigt verwaiste Display-Entities in der Welt |
| `/admx clear confirm` | `admx.admin` | Entfernt alle ADMX-Geräte der Welt (erfordert Bestätigung) |

### Berechtigungen

- `admx.admin`: Vollzugriff auf alle Befehle, Steuerungen und Datenbank-Funktionen.
- `admx.place`: Berechtigt zum Aufstellen und Konfigurieren von Fixtures.
- `admx.remove`: Erlaubt das Abbauen von Geräten.
- `admx.control`: Erlaubt das Steuern von Scheinwerfergruppen, Szenen und Farben.
- `admx.console`: Zugriff auf das Chest-UI und 3D-Hologramm-Pult.
- `admx.safe` *(Standard: alle Spieler)*: Erlaubt das Umschalten des Schutzmodus gegen Lichtblitze.

---

## 🚀 Schnellstart & Installation

### Voraussetzungen
- **Minecraft-Server:** [Paper](https://papermc.io) oder [Purpur](https://purpurmc.org) 1.21+ (Getestet auf 1.21.4+ / Paper 26.3)
- **Java:** OpenJDK 25 oder neuer

### 1. Plugin installieren
1. Baue oder lade die `ADMX.jar` herunter.
2. Platziere die Datei im `plugins/`-Ordner deines Servers.
3. Starte den Server neu.

### 2. Resource-Pack bereitstellen
ADMX benötigt für die detailgetreuen Scheinwerfergehäuse ein Resource-Pack:
1. Führe im Projektverzeichnis folgenden Befehl aus, um das Resource-Pack zu packen:
   ```bash
   ./gradlew packageResourcePack
   ```
2. Das fertige Archiv `ADMX_ResourcePack.zip` wird direkt im Root-Ordner erstellt.
3. Binde es als Server-Resource-Pack in der `server.properties` ein oder installiere es lokal im Minecraft-Client.

### 3. Erste Schritte im Spiel
1. Nimm den Zauberstab zur Hand: `/admx wand`
2. Klicke mit Rechtsklick auf einen Block, um das Platzierungsmenü zu öffnen.
3. Wähle ein Gerät (z. B. `Moving Head Spot`) und weise es einer Gruppe zu (z. B. `main_stage`).
4. Öffne das Steuerpult mit `/admx ui open` oder platziere eine 3D-Holo-Konsole in deiner Regiekabine!

---

## 🛠️ Zusätzliche Tools & Integrationen

### Art-Net Remote Bridge (`tools/artnet-bridge`)
Möchtest du deine Minecraft-Bühne direkt über **grandMA3 onPC**, **QLC+** oder **Resolume** ansteuern?
- Details und Schritt-für-Schritt-Anleitung findest du in der [Art-Net Bridge Dokumentation](tools/artnet-bridge/README.md).
- Python-Client starten:
  ```bash
  cd tools/artnet-bridge
  pip install websockets
  python admx_artnet_client.py --server ws://dein-server:8765 --token DEIN_TOKEN
  ```

### Live Audio Streamer (`tools/audio-streamer`)
Streamt den Sound deiner Soundkarte oder DJ-Software direkt in die Sound-to-Light-Engine von ADMX:
```bash
cd tools/audio-streamer
pip install pyaudiowpatch numpy
python stream_to_admx.py --loopback
```

---

## 💻 Entwicklung & Build

Das Projekt nutzt Gradle mit Kotlin DSL und Java 25:

```bash
# Repository klonen
git clone https://github.com/de-archer/admx.git
cd admx

# Tests ausführen
./gradlew test

# Plugin kompilieren und bauen
./gradlew build

# Resource-Pack bauen
./gradlew packageResourcePack
```

---

## 📄 Lizenz

Dieses Projekt ist unter der **MIT-Lizenz** lizenziert. Weitere Details findest du in der Datei [LICENSE](LICENSE).

---

*Entwickelt mit Leidenschaft für die Minecraft-Event- und Show-Community von **Archer**.*
