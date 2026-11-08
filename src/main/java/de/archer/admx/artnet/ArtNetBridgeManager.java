package de.archer.admx.artnet;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.render.fixture.FixtureState;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.function.Function;

/**
 * Verwaltet den gesamten Lebenszyklus der bidirektionalen Art-Net 4 Schnittstelle:
 * <ul>
 *   <li>Zentraler 16-Universen DMX512 Core-Bus ({@link ArtNetDmxUniverseManager})</li>
 *   <li>Nativer Art-Net 4 UDP-Node auf Port 6454 ({@link ArtNetUdpNode} inkl. {@code ArtPollReply} Auto-Discovery)</li>
 *   <li>Token-authentifizierte, bidirektionale WebSocket-Bridge ({@link ArtNetWebSocketServer}) für WAN/NAT</li>
 * </ul>
 */
public final class ArtNetBridgeManager implements AutoCloseable {

    private final AdmxPlugin plugin;
    private final ArtNetDmxUniverseManager universeManager;
    private ArtNetWebSocketServer server;
    private ArtNetUdpNode udpNode;

    private boolean enabled;
    private String bindHost;
    private int port;
    private String udpBindHost;
    private int udpPort;
    private String authToken;
    private int maxClients;
    private int rateLimitAttempts;

    public ArtNetBridgeManager(AdmxPlugin plugin) {
        this.plugin = plugin;
        this.universeManager = new ArtNetDmxUniverseManager();
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("artnet-bridge.enabled", true);
        this.bindHost = cfg.getString("artnet-bridge.bind-host", "0.0.0.0");
        this.port = cfg.getInt("artnet-bridge.port", 8765);
        // SEC-12: Nativer UDP-Port standardmäßig nur auf Loopback 127.0.0.1 binden
        this.udpBindHost = cfg.getString("artnet-bridge.udp-bind-host", "127.0.0.1");
        this.udpPort = cfg.getInt("artnet-bridge.udp-port", ArtNetUdpNode.DEFAULT_ARTNET_PORT);
        this.authToken = cfg.getString("artnet-bridge.auth-token", "");
        this.maxClients = Math.max(1, cfg.getInt("artnet-bridge.max-clients", 5));
        this.rateLimitAttempts = Math.max(3, cfg.getInt("artnet-bridge.rate-limit-max-attempts", 5));

        // Falls noch kein Token existiert, einen kryptografisch sicheren Token erzeugen und speichern
        if (authToken == null || authToken.isBlank()) {
            this.authToken = ArtNetWebSocketServer.generateSecureToken();
            cfg.set("artnet-bridge.auth-token", this.authToken);
            plugin.saveConfig();
        }
    }

    public synchronized void start() {
        if (!enabled) {
            Bukkit.getLogger().info("[ADMX-ArtNet] Bridge ist in config.yml deaktiviert.");
            return;
        }
        if (server == null || !server.isRunning()) {
            try {
                this.server = new ArtNetWebSocketServer(bindHost, port, authToken, maxClients, rateLimitAttempts);
                this.server.setIncomingPacketHandler(universeManager::handleIncomingArtNetPacket);
                this.server.start();
            } catch (Exception e) {
                Bukkit.getLogger().severe("[ADMX-ArtNet] Fehler beim Starten des WebSocket-Servers auf Port " + port + ": " + e.getMessage());
            }
        }
        if (udpNode == null || !udpNode.isRunning()) {
            this.udpNode = new ArtNetUdpNode(udpBindHost, udpPort, universeManager);
            this.udpNode.start();
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
            Bukkit.getLogger().info("[ADMX-ArtNet] WebSocket-Bridge gestoppt.");
        }
        if (udpNode != null) {
            udpNode.close();
            udpNode = null;
        }
    }

    public synchronized void regenerateToken() {
        this.authToken = ArtNetWebSocketServer.generateSecureToken();
        plugin.getConfig().set("artnet-bridge.auth-token", this.authToken);
        plugin.saveConfig();

        stop();
        start();
    }

    public ArtNetDmxUniverseManager getUniverseManager() {
        return universeManager;
    }

    public DmxUniverse getUniverse(int universeId) {
        return universeManager.getUniverse(universeId);
    }

    public ArtNetUdpNode getUdpNode() {
        return udpNode;
    }

    /**
     * Synchronisiert die DMX-Universen im aktuellen Render-Tick und streamt die zusammengeführten
     * DMX-Frames (HTP/LTP) an alle verbundenen WebSocket-Clients sowie optional über den lokalen UDP-Node.
     */
    public void onRenderTick(
            List<AdmxPlugin.PlacedFixture> fixtures,
            Function<AdmxPlugin.PlacedFixture, FixtureState> stateResolver
    ) {
        universeManager.syncUniverses(fixtures, stateResolver);

        boolean wsActive = (server != null && server.isRunning() && server.getClientCount() > 0);
        boolean udpTxActive = (udpNode != null && udpNode.isRunning() && udpNode.isLoopbackTxEnabled());
        if (!wsActive && !udpTxActive) {
            return;
        }

        int count = (fixtures != null) ? fixtures.size() : 0;
        List<byte[]> frames = universeManager.buildSyncedDmxFrames(count);
        for (byte[] frame : frames) {
            if (wsActive) {
                server.broadcastArtNetPacket(frame);
            }
            if (udpTxActive) {
                udpNode.sendArtDmxFrame(frame);
            }
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int getConnectedClientCount() {
        return (server != null) ? server.getClientCount() : 0;
    }

    public String getAuthToken() {
        return authToken;
    }

    public int getPort() {
        return port;
    }

    public String getBindHost() {
        return bindHost;
    }

    @Override
    public void close() {
        stop();
    }
}
