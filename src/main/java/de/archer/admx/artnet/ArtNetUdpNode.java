package de.archer.admx.artnet;

import org.bukkit.Bukkit;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nativer Art-Net 4 UDP-Node (Standard-Port 6454) zur direkten Kopplung der In-Game DMX-Engine
 * mit Lichtpult-Software (QLC+, grandMA, Chamsys) und Art-Net Hardware.
 * <p>
 * Unterstützt:
 * <ul>
 *   <li>Eingehende {@code ArtDmx} (OpCode 0x5000) Pakete &rarr; Direkte Einspeisung in {@link ArtNetDmxUniverseManager}</li>
 *   <li>Eingehende {@code ArtPoll} (OpCode 0x2000) Pakete &rarr; Standardkonforme 239-Byte {@code ArtPollReply} (OpCode 0x2100) Auto-Discovery</li>
 *   <li>Ausgehende {@code ArtDmx} (OpCode 0x5000) Pakete an konfigurierte Ziel-IP</li>
 * </ul>
 * <p>
 * Sicherheits-Invarianten (SEC-12):
 * <ul>
 *   <li>Standardmäßig nur an Loopback ({@code 127.0.0.1}) gebunden, um unauthentifiziertes UDP-Spoofing aus dem WAN zu verhindern.</li>
 *   <li>Strikte Rate-Limitierung für {@code ArtPollReply} (max. 1 Antwort alle 2000 ms pro Quell-IP, begrenzte Map-Größe), um UDP-Amplification-Angriffe auszuschließen.</li>
 * </ul>
 */
public final class ArtNetUdpNode implements AutoCloseable {

    public static final int DEFAULT_ARTNET_PORT = 6454;
    public static final int OPCODE_POLL = 0x2000;
    public static final int OPCODE_POLL_REPLY = 0x2100;
    public static final int POLL_REPLY_SIZE = 239;
    private static final long POLL_RATE_LIMIT_MS = 2000L;
    private static final int MAX_TRACKED_POLL_IPS = 256;

    private final String bindHost;
    private final int port;
    private final ArtNetDmxUniverseManager universeManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean listeningOnStandardPort = false;
    private volatile boolean loopbackTxEnabled = false;
    private volatile InetAddress txTargetAddress;
    private volatile int txTargetPort = DEFAULT_ARTNET_PORT;

    private DatagramSocket socket;
    private Thread rxThread;
    private final Map<String, Long> lastPollReplyByIp = new ConcurrentHashMap<>();

    public ArtNetUdpNode(String bindHost, int port, ArtNetDmxUniverseManager universeManager) {
        this.bindHost = (bindHost == null || bindHost.isBlank()) ? "127.0.0.1" : bindHost;
        this.port = (port <= 0 || port > 65535) ? DEFAULT_ARTNET_PORT : port;
        this.universeManager = universeManager;
        try {
            this.txTargetAddress = InetAddress.getByName("127.0.0.1");
        } catch (Exception ignored) {
        }
    }

    public synchronized void start() {
        if (running.get()) return;
        try {
            DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            try {
                ds.bind(new InetSocketAddress(InetAddress.getByName(bindHost), port));
                this.listeningOnStandardPort = true;
            } catch (Exception bindEx) {
                // Falls QLC+ auf demselben PC Port 6454 exklusiv belegt hat: Ephemerer Socket für TX
                ds.bind(new InetSocketAddress(InetAddress.getByName(bindHost), 0));
                this.listeningOnStandardPort = false;
                Bukkit.getLogger().info("[ADMX-ArtNet] UDP Port " + port + " bereits lokal durch Lichtsoftware belegt – nutze Koexistenz-Modus (WebSocket RX/TX + UDP TX bereit).");
            }
            this.socket = ds;
            this.running.set(true);

            this.rxThread = Thread.ofVirtual().name("ADMX-ArtNet-UDP-Node").start(this::receiveLoop);
            if (listeningOnStandardPort) {
                Bukkit.getLogger().info("[ADMX-ArtNet] Nativer Art-Net 4 UDP-Node aktiv auf " + bindHost + ":" + port + " (OpDmx RX + ArtPoll Discovery bereit)");
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ADMX-ArtNet] Konnte Art-Net UDP-Node nicht starten: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                int len = pkt.getLength();
                if (len < 12) continue;

                // Prüfe "Art-Net\0" Header
                boolean validHeader = true;
                for (int i = 0; i < ArtDmxPacket.HEADER_ID.length; i++) {
                    if (buf[i] != ArtDmxPacket.HEADER_ID[i]) {
                        validHeader = false;
                        break;
                    }
                }
                if (!validHeader) continue;

                int opCode = (buf[8] & 0xFF) | ((buf[9] & 0xFF) << 8);
                if (opCode == ArtDmxPacket.OPCODE_DMX) {
                    universeManager.handleIncomingArtNetPacket(buf, len);
                } else if (opCode == OPCODE_POLL && len >= 14) {
                    handleArtPoll(pkt.getAddress(), pkt.getPort());
                }
            } catch (Exception e) {
                if (running.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    /**
     * Beantwortet ArtPoll-Anfragen mit einem RFC-konformen 239-Byte ArtPollReply-Paket,
     * geschützt durch striktes Per-IP Rate-Limiting gegen UDP-Amplification (SEC-12).
     */
    private void handleArtPoll(InetAddress senderAddr, int senderPort) {
        if (senderAddr == null || socket == null || socket.isClosed()) return;
        String ipKey = senderAddr.getHostAddress();
        long now = System.currentTimeMillis();

        if (lastPollReplyByIp.size() > MAX_TRACKED_POLL_IPS) {
            lastPollReplyByIp.clear();
        }
        Long last = lastPollReplyByIp.get(ipKey);
        if (last != null && (now - last) < POLL_RATE_LIMIT_MS) {
            return; // SEC-12: Rate-Limit gegen Amplification
        }
        lastPollReplyByIp.put(ipKey, now);

        try {
            byte[] reply = buildArtPollReply(socket.getLocalAddress(), port);
            DatagramPacket outPkt = new DatagramPacket(reply, reply.length, senderAddr, senderPort);
            socket.send(outPkt);
        } catch (Exception ignored) {
        }
    }

    /**
     * Erstellt ein standardkonformes 239-Byte ArtPollReply-Paket (OpCode 0x2100).
     */
    public static byte[] buildArtPollReply(InetAddress localIp, int port) {
        byte[] ipBytes = (localIp != null && localIp.getAddress().length == 4)
                ? localIp.getAddress()
                : new byte[]{127, 0, 0, 1};
        return buildArtPollReply(ipBytes, port);
    }

    public static byte[] buildArtPollReply(byte[] ipBytes, int port) {
        byte[] reply = new byte[POLL_REPLY_SIZE];
        System.arraycopy(ArtDmxPacket.HEADER_ID, 0, reply, 0, 8);

        // OpCode 0x2100 (Little-Endian)
        reply[8] = (byte) (OPCODE_POLL_REPLY & 0xFF);
        reply[9] = (byte) ((OPCODE_POLL_REPLY >> 8) & 0xFF);

        // IP Address (4 Bytes)
        byte[] safeIp = (ipBytes != null && ipBytes.length == 4) ? ipBytes : new byte[]{127, 0, 0, 1};
        System.arraycopy(safeIp, 0, reply, 10, 4);

        // Port (Little-Endian: 0x36, 0x19 = 6454)
        reply[14] = (byte) (port & 0xFF);
        reply[15] = (byte) ((port >> 8) & 0xFF);

        // Firmware Version (1.0)
        reply[16] = 1;
        reply[17] = 0;

        // NetSwitch (0), SubSwitch (0)
        reply[18] = 0;
        reply[19] = 0;

        // Status1: Normal Indicators
        reply[23] = (byte) 0xE0;

        // ShortName (18 Bytes ab Index 26)
        byte[] shortName = "ADMX-DMX512-Node".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(shortName, 0, reply, 26, Math.min(shortName.length, 17));

        // LongName (64 Bytes ab Index 44)
        byte[] longName = "ADMX Minecraft Professional DMX512 & Art-Net 4 Stage Engine".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(longName, 0, reply, 44, Math.min(longName.length, 63));

        // NumPorts (Big-Endian ab Index 172: 4 Ports)
        reply[172] = 0;
        reply[173] = 4;

        // PortTypes (4 Bytes ab Index 174: 0xC0 = DMX512 Input + Output)
        for (int i = 0; i < 4; i++) {
            reply[174 + i] = (byte) 0xC0;
            reply[178 + i] = (byte) 0x80; // GoodInput
            reply[182 + i] = (byte) 0x80; // GoodOutput
            reply[186 + i] = (byte) i;    // SwIn Universe 0..3
            reply[190 + i] = (byte) i;    // SwOut Universe 0..3
        }

        return reply;
    }

    public void sendArtDmxFrame(byte[] artDmxBytes) {
        if (!running.get() || !loopbackTxEnabled || socket == null || socket.isClosed() || txTargetAddress == null) {
            return;
        }
        try {
            DatagramPacket pkt = new DatagramPacket(artDmxBytes, artDmxBytes.length, txTargetAddress, txTargetPort);
            socket.send(pkt);
        } catch (Exception ignored) {
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isListeningOnStandardPort() {
        return listeningOnStandardPort;
    }

    public boolean isBoundToStandardPort() {
        return listeningOnStandardPort;
    }

    public boolean isLoopbackTxEnabled() {
        return loopbackTxEnabled;
    }

    public boolean isBroadcastToLocalhost() {
        return loopbackTxEnabled;
    }

    public void setLoopbackTxEnabled(boolean enabled) {
        this.loopbackTxEnabled = enabled;
    }

    public void setBroadcastToLocalhost(boolean enabled) {
        this.loopbackTxEnabled = enabled;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
            socket = null;
        }
    }
}
