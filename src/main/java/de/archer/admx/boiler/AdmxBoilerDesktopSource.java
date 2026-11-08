package de.archer.admx.boiler;

import com.google.gson.JsonObject;
import de.archer.admx.AdmxPlugin;
import de.archer.admx.boiler.plasma.PlasmaWindowManager;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;
import net.somewhatcity.boiler.api.util.DragPhase;
import org.bukkit.command.CommandSender;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.util.Map;

/**
 * Interaktive Desktop-UI ("ADMX Plasma OS") für Boiler-Displays ({@link IBoilerSource}).
 * <p>
 * Bietet eine vollwertige, moderne KDE-Plasma-Desktop-Oberfläche mit:
 * <ul>
 *     <li><b>Node-Based Flowboard Studio:</b> Freies Platzieren modularer Sequence Bricks
 *         und Verbindung über geschwungene, leuchtende Bezier-Patchkabel</li>
 *     <li><b>DMX512 Fader Wing:</b> 8 Motorfader, 2D X/Y Vector Touchpad, HSB-Farbpalette</li>
 *     <li><b>Auto-Show Studio:</b> Multi-Pass Song-DNA Learner mit 8 Quick-Recall Slots</li>
 *     <li><b>Sound-to-Light DSP Studio:</b> Live 24-Band FFT-Spektrum und manuelle BPM-Eingabe</li>
 *     <li><b>Plasma Taskleiste:</b> Start-Menü [✦ ADMX], Fenster-Reiter, Live-BPM-Uhr und Not-Aus (DBO)</li>
 * </ul>
 * <p>
 * Sicherheits-Invariante (SEC-01): Jede Interaktion ({@code onClick}, {@code onDrag}, {@code onScroll}, {@code onKey})
 * prüft zwingend {@code admx.console}, {@code admx.control} oder {@code admx.admin}.
 */
public final class AdmxBoilerDesktopSource implements IBoilerSource {

    public static final int VIRTUAL_WIDTH = 640;
    public static final int VIRTUAL_HEIGHT = 360;

    private static final Color BG_DARK = new Color(10, 13, 20);
    private static final Color TEXT_WHITE = new Color(238, 244, 252);

    private IBoilerDisplay display;
    private final PlasmaWindowManager windowManager = new PlasmaWindowManager();

    private volatile int cursorX = -1;
    private volatile int cursorY = -1;

    @Override
    public void load(IBoilerDisplay display, JsonObject data) {
        this.display = display;
        if (data != null) {
            if (data.has("universeId")) {
                int uid = Math.clamp(data.get("universeId").getAsInt(), 0, DmxUniverse.MAX_UNIVERSES - 1);
                windowManager.setUniverseId(uid);
            }
            if (data.has("faderBank")) {
                int bank = Math.clamp(data.get("faderBank").getAsInt(), 0, 63);
                windowManager.mixerWindow().setFaderBank(bank);
            }
        }
    }

    @Override
    public void unload() {
        this.display = null;
    }

    public PlasmaWindowManager windowManager() {
        return windowManager;
    }

    public void persistState() {
        IBoilerDisplay d = this.display;
        if (d == null) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("universeId", windowManager.universeId());
            obj.addProperty("faderBank", windowManager.mixerWindow().faderBank());
            d.saveSourceData(obj);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasPermission(CommandSender sender) {
        return sender != null && (sender.hasPermission("admx.admin")
                || sender.hasPermission("admx.control")
                || sender.hasPermission("admx.console"));
    }

    private Point2D toVirtual(int rawX, int rawY) {
        IBoilerDisplay d = this.display;
        int w = (d != null && d.width() > 0) ? d.width() : VIRTUAL_WIDTH;
        int h = (d != null && d.height() > 0) ? d.height() : VIRTUAL_HEIGHT;
        double scale = Math.min(w / (double) VIRTUAL_WIDTH, h / (double) VIRTUAL_HEIGHT);
        double left = (w - VIRTUAL_WIDTH * scale) * 0.5;
        double top = (h - VIRTUAL_HEIGHT * scale) * 0.5;
        return new Point2D.Double((rawX - left) / scale, (rawY - top) / scale);
    }

    @Override
    public void onCursorMove(CommandSender sender, int x, int y) {
        Point2D pt = toVirtual(x, y);
        this.cursorX = (int) Math.round(pt.getX());
        this.cursorY = (int) Math.round(pt.getY());
    }

    @Override
    public void onDrag(CommandSender sender, int x, int y, boolean right, DragPhase phase) {
        if (!hasPermission(sender)) return;
        Point2D pt = toVirtual(x, y);
        int vx = (int) Math.round(pt.getX());
        int vy = (int) Math.round(pt.getY());
        this.cursorX = vx;
        this.cursorY = vy;

        AdmxPlugin plugin = AdmxBoilerIntegration.getPlugin();
        DmxUniverse uni = (plugin != null && plugin.getArtNetBridgeManager() != null)
                ? plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(windowManager.universeId())
                : null;

        windowManager.handleDrag(sender, vx, vy, right, phase, plugin, uni);
        persistState();
    }

    @Override
    public void onClick(CommandSender sender, int x, int y, boolean right) {
        if (!hasPermission(sender)) return;
        Point2D pt = toVirtual(x, y);
        int vx = (int) Math.round(pt.getX());
        int vy = (int) Math.round(pt.getY());
        this.cursorX = vx;
        this.cursorY = vy;

        AdmxPlugin plugin = AdmxBoilerIntegration.getPlugin();
        DmxUniverse uni = (plugin != null && plugin.getArtNetBridgeManager() != null)
                ? plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(windowManager.universeId())
                : null;

        windowManager.handleClick(sender, vx, vy, right, plugin, uni);
        persistState();
    }

    @Override
    public void onScroll(CommandSender sender, int x, int y, int delta) {
        if (!hasPermission(sender) || delta == 0) return;
        Point2D pt = toVirtual(x, y);
        int vx = (int) Math.round(pt.getX());
        int vy = (int) Math.round(pt.getY());

        AdmxPlugin plugin = AdmxBoilerIntegration.getPlugin();
        DmxUniverse uni = (plugin != null && plugin.getArtNetBridgeManager() != null)
                ? plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(windowManager.universeId())
                : null;

        windowManager.handleScroll(sender, vx, vy, delta, plugin, uni);
        persistState();
    }

    @Override
    public void onKey(CommandSender sender, String key) {
        if (!hasPermission(sender) || key == null) return;
        AdmxPlugin plugin = AdmxBoilerIntegration.getPlugin();
        DmxUniverse uni = (plugin != null && plugin.getArtNetBridgeManager() != null)
                ? plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(windowManager.universeId())
                : null;

        windowManager.handleKey(sender, key, plugin, uni);
        persistState();
    }

    @Override
    public void draw(Graphics2D rawGraphics, Rectangle viewport) {
        Graphics2D g = (Graphics2D) rawGraphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(BG_DARK);
            g.fillRect(0, 0, viewport.width, viewport.height);

            double scale = Math.min(viewport.width / (double) VIRTUAL_WIDTH, viewport.height / (double) VIRTUAL_HEIGHT);
            double left = (viewport.width - VIRTUAL_WIDTH * scale) * 0.5;
            double top = (viewport.height - VIRTUAL_HEIGHT * scale) * 0.5;
            g.translate(left, top);
            g.scale(scale, scale);

            AdmxPlugin plugin = AdmxBoilerIntegration.getPlugin();
            if (plugin == null || plugin.getArtNetBridgeManager() == null) {
                g.setColor(TEXT_WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
                g.drawString("ADMX Plasma OS wird initialisiert...", 180, 180);
                return;
            }

            DmxUniverse uni = plugin.getArtNetBridgeManager().getUniverseManager().getUniverse(windowManager.universeId());
            windowManager.drawDesktop(g, plugin, uni, cursorX, cursorY);
        } finally {
            g.dispose();
        }
    }

    @Override
    public Map<String, CommandArgumentType> command() {
        return Map.of();
    }
}
