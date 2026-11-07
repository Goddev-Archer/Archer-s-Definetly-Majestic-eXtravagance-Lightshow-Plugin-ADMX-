package de.archer.admx.artnet;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.render.fixture.FixtureState;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Zentraler Lifecycle- & Übertragungs-Manager für die Art-Net WebSocket-Bridge.
 * <p>
 * Erzeugt aus den aktuellen Licht-Zuständen der platzierten Scheinwerfer
 * normgerechte DMX512-Frames und streamt diese im 20-Hz Server-Takt
 * hochgesichert über WebSocket an verbundene Client-PCs.
 */
public final class ArtNetBridgeManager implements AutoCloseable {

    private final AdmxPlugin plugin;
    private final ArtNetDmxUniverseManager universeManager = new ArtNetDmxUniverseManager();

    private boolean enabled = true;
    private String bindHost = "0.0.0.0";
    private int port = 8765;
    private String authToken = "";
    private int maxClients = 5;
    private int rateLimitAttempts = 5;

    private ArtNetWebSocketServer server;

    public ArtNetBridgeManager(AdmxPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("artnet-bridge.enabled", true);
        this.bindHost = cfg.getString("artnet-bridge.bind-host", "0.0.0.0");
        this.port = cfg.getInt("artnet-bridge.port", 8765);
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
        if (server != null && server.isRunning()) return;

        try {
            this.server = new ArtNetWebSocketServer(bindHost, port, authToken, maxClients, rateLimitAttempts);
            this.server.start();
        } catch (Exception e) {
            Bukkit.getLogger().severe("[ADMX-ArtNet] Fehler beim Starten des WebSocket-Servers auf Port " + port + ": " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
            Bukkit.getLogger().info("[ADMX-ArtNet] WebSocket-Bridge gestoppt.");
        }
    }

    public synchronized void regenerateToken() {
        this.authToken = ArtNetWebSocketServer.generateSecureToken();
        plugin.getConfig().set("artnet-bridge.auth-token", this.authToken);
        plugin.saveConfig();

        // Server neu starten, um neues Token anzuwenden
        stop();
        start();
    }

    /**
     * Wird in jedem Render-Tick von ADMX aufgerufen.
     * Erstellt DMX-Frames und streamt diese ohne Verzögerung an verbundene Clients.
     */
    public void onRenderTick(List<AdmxPlugin.PlacedFixture> fixtures, java.util.function.Function<AdmxPlugin.PlacedFixture, FixtureState> stateResolver) {
        if (server == null || !server.isRunning() || server.getClientCount() == 0) {
            return;
        }

        List<byte[]> frames = universeManager.buildDmxFrames(fixtures, stateResolver);
        for (byte[] frame : frames) {
            server.broadcastArtNetPacket(frame);
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
