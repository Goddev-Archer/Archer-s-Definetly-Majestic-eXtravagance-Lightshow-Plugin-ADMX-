package de.archer.admx.artnet;

import org.bukkit.Bukkit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autarker, performanter und hochsicherer RFC 6455 WebSocket-Server für Art-Net Streaming.
 * <p>
 * Implementiert maximale Sicherheits- und Datenschutzstandards:
 * <ul>
 *   <li>SEC-01 &amp; SEC-02: Kryptografische Token-Authentifizierung mit Constant-Time Vergleich (MessageDigest.isEqual).</li>
 *   <li>SEC-03: Brute-Force Abwehr &amp; temporäre IP-Sperre nach Fehlversuchen.</li>
 *   <li>SEC-04: Verbindungsdeckel (Max Clients) gegen Socket-Erschöpfung.</li>
 *   <li>PERF-09: Vollständige Entkopplung vom Paper-Hauptthread über asynchrones I/O mit Drop-Tail Pufferung (Zero-Lag).</li>
 *   <li>Datenschutz: Überträgt ausschließlich rohe DMX-Bytes (0..255). Keinerlei Spielernamen, Koordinaten oder Chatdaten!</li>
 * </ul>
 */
public final class ArtNetWebSocketServer implements AutoCloseable {

    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final long BAN_DURATION_MS = 15 * 60 * 1000L; // 15 Minuten Sperre

    private final String bindHost;
    private final int port;
    private final String expectedToken;
    private final int maxClients;
    private final int maxFailedAttempts;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    // Aktive verbundene Clients
    private final List<WsClientConnection> connectedClients = new CopyOnWriteArrayList<>();

    // Sicherheits-Tracking für Brute-Force Schutz
    private static final class IpSecurityState {
        int failedAttempts = 0;
        long bannedUntilMs = 0L;
    }
    private final Map<String, IpSecurityState> ipSecurityMap = new ConcurrentHashMap<>();

    public ArtNetWebSocketServer(String bindHost, int port, String expectedToken, int maxClients, int maxFailedAttempts) {
        this.bindHost = (bindHost != null && !bindHost.isBlank()) ? bindHost : "0.0.0.0";
        this.port = port;
        this.expectedToken = (expectedToken != null && !expectedToken.isBlank()) ? expectedToken : generateSecureToken();
        this.maxClients = Math.max(1, maxClients);
        this.maxFailedAttempts = Math.max(3, maxFailedAttempts);
    }

    public static String generateSecureToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;

        InetAddress addr = InetAddress.getByName(bindHost);
        this.serverSocket = new ServerSocket(port, 50, addr);
        this.running.set(true);

        this.acceptThread = new Thread(this::runAcceptLoop, "ADMX-ArtNet-WS-Acceptor");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();

        Bukkit.getLogger().info("[ADMX-ArtNet] WebSocket-Bridge lauscht sicher auf " + bindHost + ":" + port);
    }

    private void runAcceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000); // 5 Sekunden Handshake-Timeout

                String clientIp = socket.getInetAddress().getHostAddress();

                // 1. IP-Ban Prüfung
                if (isIpBanned(clientIp)) {
                    Bukkit.getLogger().warning("[ADMX-ArtNet] Verbindung von gesperrter IP " + clientIp + " abgewiesen.");
                    socket.close();
                    continue;
                }

                // 2. Client-Deckel Prüfung
                if (connectedClients.size() >= maxClients) {
                    sendHttpError(socket, "503 Service Unavailable", "Max clients reached");
                    socket.close();
                    continue;
                }

                // 3. Handshake asynchron in Hintergrund-Thread abwickeln
                Thread.ofVirtual().start(() -> handleNewConnection(socket, clientIp));
            } catch (Exception e) {
                if (!running.get()) break;
            }
        }
    }

    private void handleNewConnection(Socket socket, String clientIp) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            if (read <= 0) {
                socket.close();
                return;
            }

            String request = new String(buffer, 0, read, StandardCharsets.UTF_8);
            String[] lines = request.split("\r\n");
            if (lines.length == 0) {
                socket.close();
                return;
            }

            String firstLine = lines[0]; // z.B. "GET /artnet?token=xyz HTTP/1.1"
            String wsKey = null;
            String authHeader = null;

            for (String line : lines) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    wsKey = line.substring(18).trim();
                } else if (line.toLowerCase().startsWith("authorization:")) {
                    authHeader = line.substring(14).trim();
                }
            }

            // Token aus Query oder Header extrahieren
            String tokenProvided = extractToken(firstLine, authHeader);

            // Token sicher validieren (Constant-Time)
            if (!validateTokenConstantTime(tokenProvided)) {
                registerFailedAttempt(clientIp);
                Bukkit.getLogger().warning("[ADMX-ArtNet] Fehlgeschlagene Authentifizierung von IP " + clientIp);
                sendHttpError(socket, "401 Unauthorized", "Invalid or missing Art-Net authentication token");
                socket.close();
                return;
            }

            if (wsKey == null || wsKey.isBlank()) {
                sendHttpError(socket, "400 Bad Request", "Missing Sec-WebSocket-Key");
                socket.close();
                return;
            }

            // Erfolgreiche Authentifizierung -> Reset Fehlversuche
            resetFailedAttempts(clientIp);

            // WebSocket Handshake Response senden
            String acceptKey = computeWebSocketAccept(wsKey);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            socket.setSoTimeout(0); // Timeout nach Handshake aufheben für langlebige Streams

            WsClientConnection client = new WsClientConnection(socket, in, out, clientIp);
            connectedClients.add(client);
            Bukkit.getLogger().info("[ADMX-ArtNet] Neuer Art-Net Client verbunden: " + clientIp + " (Aktive Clients: " + connectedClients.size() + ")");

            client.runReadLoop(() -> removeClient(client));
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private String extractToken(String firstLine, String authHeader) {
        if (firstLine != null && firstLine.contains("?")) {
            int qIdx = firstLine.indexOf('?');
            int sIdx = firstLine.indexOf(' ', qIdx);
            String query = (sIdx != -1) ? firstLine.substring(qIdx + 1, sIdx) : firstLine.substring(qIdx + 1);
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return param.substring(6);
                }
            }
        }
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }
            return authHeader;
        }
        return null;
    }

    private boolean validateTokenConstantTime(String provided) {
        if (provided == null || expectedToken == null) return false;
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String computeWebSocketAccept(String key) throws NoSuchAlgorithmException {
        String input = key + WS_MAGIC_GUID;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(input.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    }

    private void sendHttpError(Socket socket, String status, String message) {
        try {
            OutputStream out = socket.getOutputStream();
            String res = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + message.length() + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + message;
            out.write(res.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {}
    }

    private boolean isIpBanned(String ip) {
        IpSecurityState state = ipSecurityMap.get(ip);
        if (state == null) return false;
        long now = System.currentTimeMillis();
        if (now < state.bannedUntilMs) {
            return true;
        }
        if (state.bannedUntilMs > 0 && now >= state.bannedUntilMs) {
            state.failedAttempts = 0;
            state.bannedUntilMs = 0;
        }
        return false;
    }

    private void registerFailedAttempt(String ip) {
        IpSecurityState state = ipSecurityMap.computeIfAbsent(ip, k -> new IpSecurityState());
        state.failedAttempts++;
        if (state.failedAttempts >= maxFailedAttempts) {
            state.bannedUntilMs = System.currentTimeMillis() + BAN_DURATION_MS;
            Bukkit.getLogger().warning("[ADMX-ArtNet] IP " + ip + " wurde wegen wiederholter Fehlversuche für 15 Minuten temporär gesperrt!");
        }
    }

    private void resetFailedAttempts(String ip) {
        ipSecurityMap.remove(ip);
    }

    private void removeClient(WsClientConnection client) {
        connectedClients.remove(client);
        client.close();
        Bukkit.getLogger().info("[ADMX-ArtNet] Client getrennt: " + client.ip + " (Verbleibend: " + connectedClients.size() + ")");
    }

    /**
     * Sendet ein Art-Net Binärpaket an alle autorisierten Clients.
     * Erzeugt keine Server-Lags: Langsame Clients werden gepuffert bzw. übersprungen.
     */
    public void broadcastArtNetPacket(byte[] artNetPacket) {
        if (!running.get() || connectedClients.isEmpty() || artNetPacket == null) return;

        byte[] wsFrame = wrapInBinaryWebSocketFrame(artNetPacket);
        for (WsClientConnection client : connectedClients) {
            client.sendFrameAsync(wsFrame);
        }
    }

    /**
     * Kapselt Nutzdaten in einen RFC 6455 Binary Frame (OpCode 0x02, FIN = 1, Unmasked).
     */
    private static byte[] wrapInBinaryWebSocketFrame(byte[] payload) {
        int len = payload.length;
        byte[] frame;
        if (len <= 125) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x82; // FIN + Binary
            frame[1] = (byte) len;
            System.arraycopy(payload, 0, frame, 2, len);
        } else if (len <= 65535) {
            frame = new byte[4 + len];
            frame[0] = (byte) 0x82;
            frame[1] = (byte) 126;
            frame[2] = (byte) ((len >> 8) & 0xFF);
            frame[3] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, frame, 4, len);
        } else {
            frame = new byte[10 + len];
            frame[0] = (byte) 0x82;
            frame[1] = (byte) 127;
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((len >> ((7 - i) * 8)) & 0xFF);
            }
            System.arraycopy(payload, 0, frame, 10, len);
        }
        return frame;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}

        for (WsClientConnection client : connectedClients) {
            client.close();
        }
        connectedClients.clear();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getClientCount() {
        return connectedClients.size();
    }

    public String getExpectedToken() {
        return expectedToken;
    }

    public int getPort() {
        return port;
    }

    public String getBindHost() {
        return bindHost;
    }

    /**
     * Repräsentiert eine einzelne Client-Verbindung.
     */
    private static final class WsClientConnection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String ip;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private volatile byte[] pendingLatestFrame = null;
        private final AtomicBoolean isWriting = new AtomicBoolean(false);

        public WsClientConnection(Socket socket, InputStream in, OutputStream out, String ip) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.ip = ip;
        }

        public void sendFrameAsync(byte[] frame) {
            if (!open.get()) return;
            // Drop-Tail: Immer das absolut aktuellste Frame vorhalten, niemals Stau erzeugen!
            this.pendingLatestFrame = frame;

            if (isWriting.compareAndSet(false, true)) {
                Thread.ofVirtual().start(this::drainPendingFrames);
            }
        }

        private void drainPendingFrames() {
            try {
                while (open.get()) {
                    byte[] frame = pendingLatestFrame;
                    pendingLatestFrame = null;
                    if (frame == null) break;

                    out.write(frame);
                    out.flush();
                }
            } catch (Exception e) {
                close();
            } finally {
                isWriting.set(false);
                if (pendingLatestFrame != null && open.get()) {
                    if (isWriting.compareAndSet(false, true)) {
                        Thread.ofVirtual().start(this::drainPendingFrames);
                    }
                }
            }
        }

        public void runReadLoop(Runnable onDisconnect) {
            try {
                while (open.get()) {
                    int b1 = in.read();
                    if (b1 == -1) break;

                    int b2 = in.read();
                    if (b2 == -1) break;

                    int opcode = b1 & 0x0F;
                    boolean masked = (b2 & 0x80) != 0;
                    long payloadLen = b2 & 0x7F;

                    if (payloadLen == 126) {
                        int h = in.read();
                        int l = in.read();
                        payloadLen = ((h << 8) | l) & 0xFFFF;
                    } else if (payloadLen == 127) {
                        payloadLen = 0;
                        for (int i = 0; i < 8; i++) {
                            payloadLen = (payloadLen << 8) | in.read();
                        }
                    }

                    byte[] maskingKey = new byte[4];
                    if (masked) {
                        in.readNBytes(maskingKey, 0, 4);
                    }

                    byte[] payload = in.readNBytes((int) payloadLen);
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
                        }
                    }

                    // Ping (0x09) beantworten mit Pong (0x0A)
                    if (opcode == 0x09) {
                        sendPong(payload);
                    } else if (opcode == 0x08) { // Close
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                close();
                onDisconnect.run();
            }
        }

        private void sendPong(byte[] pingPayload) {
            try {
                byte[] pong = new byte[2 + pingPayload.length];
                pong[0] = (byte) 0x8A; // FIN + Pong
                pong[1] = (byte) pingPayload.length;
                System.arraycopy(pingPayload, 0, pong, 2, pingPayload.length);
                out.write(pong);
                out.flush();
            } catch (Exception ignored) {}
        }

        public void close() {
            if (open.compareAndSet(true, false)) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
