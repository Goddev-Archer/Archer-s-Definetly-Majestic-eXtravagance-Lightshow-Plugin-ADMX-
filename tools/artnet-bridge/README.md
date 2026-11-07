# ADMX Remote Art-Net WebSocket Bridge 🌐💡

Diese Bridge ermöglicht es, die Live-DMX512-Steuerdaten der in Minecraft platzierten ADMX-Scheinwerfer über das weltweite Internet (z. B. von einem Cloud-Server in Frankfurt) an deinen lokalen PC zu streamen – **ohne Router-Portfreigaben, ohne Port-Forwarding und ohne VPN**, selbst hinter IPv6 / DS-Lite Anschlüssen!

---

## 1. Funktionsweise & Architektur

1. **Server-Seite (ADMX Plugin in Minecraft):**
   - In jedem Render-Tick berechnet ADMX die exakten Pan-, Tilt-, Dimmer-, Strobe-, Zoom- und RGB-Werte aller Bühnenscheinwerfer.
   - Der [`ArtNetDmxUniverseManager`](file:///c:/dev/Archer's%20Definetly%20Majestic%20eXtravagance%20Lightshow-Plugin%20(ADMX)/src/main/java/de/archer/admx/artnet/ArtNetDmxUniverseManager.java) packt diese Werte in standardkonforme Art-Net 4 `ArtDmx`-Pakete (530 Bytes pro Universum).
   - Der integrierte [`ArtNetWebSocketServer`](file:///c:/dev/Archer's%20Definetly%20Majestic%20eXtravagance%20Lightshow-Plugin%20(ADMX)/src/main/java/de/archer/admx/artnet/ArtNetWebSocketServer.java) streamt die Pakete hochgesichert per RFC 6455 Binary WebSocket an autorisierte Clients.

2. **Client-Seite (Python-Daemon auf deinem PC):**
   - Baut eine **ausgehende** WebSocket-Verbindung zum Server auf. Ausgehende TCP/WSS-Verbindungen werden von jeder Fritz!Box und Unternehmensfirewall standardmäßig erlaubt.
   - Empfängt die binären Frames und strahlt sie lokal als UDP-Pakete an `127.0.0.1:6454` (Standard-Art-Net-Port) oder dein lokales LAN (`255.255.255.255`) aus.
   - Jede Lichtsteuerungs-Software (grandMA onPC, QLC+, Resolume Arena, Madrix, DMXControl) empfängt das Signal sofort.

---

## 2. Sicherheits- & Datenschutz-Architektur (Datensicherheit)

Bei der Entwicklung standen Sicherheit und Datensparsamkeit an oberster Stelle:

1. **Kryptografische Token-Authentifizierung (SEC-01):**
   - Verbindungen erfordern einen 32-Zeichen Base64-Token (`/admx artnet token`).
   - Der Server prüft den Token mittels `MessageDigest.isEqual(...)` in konstanter Zeit, wodurch Timing-Attacks unmöglich sind.
2. **Brute-Force & Denial-of-Service Schutz (SEC-03):**
   - Wiederholte Fehlversuche sperren die anfragende IP-Adresse vollautomatisch für 15 Minuten.
   - Hard-Limit für maximale gleichzeitige Client-Verbindungen (`max-clients: 5`).
3. **100 % Datensparsamkeit (Zero PII):**
   - Es werden **ausschließlich rohe DMX-Byte-Arrays** (Kanäle 1..512) übertragen.
   - Keine Spielernamen, keine UUIDs, keine Chat-Nachrichten, keine Welt-Koordinaten, keine IPs anderer Nutzer.
4. **Zero-Lag & Puffer-Schutz (PERF-09):**
   - Der Server verwendet Java Virtual Threads und Drop-Tail Pufferung: Ist die Internetverbindung eines Clients kurzzeitig langsam, werden ältere Frames sofort verworfen. Es gibt keinen Speicherstau und keine Verzögerung auf dem Minecraft-Server.

---

## 3. Schnellanleitung (In 2 Minuten startklar)

### Schritt 1: Token auf dem Server abrufen
Gib im Spiel als Admin folgenden Befehl ein:
```
/admx artnet token
```
Du erhältst deinen geheimen Token und die fertige WebSocket-URL angezeigt.

### Schritt 2: Client-Bridge starten
Installiere die benötigte Python-Bibliothek auf deinem Rechner:
```bash
pip install websockets
```

Starte die Bridge:
```bash
python admx_artnet_client.py --server ws://meine-server-ip:8765 --token DEIN_TOKEN
```

### Schritt 3: Lichtsoftware verbinden
- **QLC+:** Öffne *Eingänge/Ausgänge*, wähle *ArtNet (127.0.0.1)* als Eingang für Universum 1.
- **grandMA onPC:** Gehe zu *Setup -> Network Protocols -> Art-Net*, schalte *Art-Net In* ein und wähle Subnet 0, Universe 0.
- **Resolume Arena:** Unter *DMX Preferences* auf *Art-Net Input* stellen.

---

## 4. Produktion & Verschlüsselung (WSS via Nginx Reverse Proxy)

Für maximale Sicherheit im öffentlichen Internet empfiehlt sich ein TLS-Terminierungs-Proxy (z. B. Nginx) auf dem Server:

```nginx
server {
    listen 443 ssl http2;
    server_name artnet.meinedomain.de;

    ssl_certificate /etc/letsencrypt/live/meinedomain.de/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/meinedomain.de/privkey.pem;

    location /artnet {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }
}
```
Client-Verbindung dann einfach über:
```bash
python admx_artnet_client.py --server wss://artnet.meinedomain.de --token DEIN_TOKEN
```
