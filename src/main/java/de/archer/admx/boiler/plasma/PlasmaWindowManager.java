package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.util.DragPhase;
import org.bukkit.command.CommandSender;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vollwertiger Fenstermanager im KDE Plasma Style ("ADMX Plasma OS") für das Boiler-Display.
 * <p>
 * Verwaltet eine verschiebbare 640×336 Arbeitsfläche mit echten Fenstern (Z-Order, Dragging,
 * Minimieren, Maximieren auf Vollbild) und eine dauerhafte Plasma-Taskleiste (Y = 336..360)
 * mit App-Launcher [✦ ADMX], Fenster-Reitern, BPM-Uhr und Not-Aus (DBO).
 */
public final class PlasmaWindowManager {

    public static final int WORKSPACE_WIDTH = 640;
    public static final int WORKSPACE_HEIGHT = 336;
    public static final int TASKBAR_HEIGHT = 24;

    private static final Color TASKBAR_BG = new Color(14, 18, 28, 240);
    private static final Color TASKBAR_BORDER = new Color(34, 44, 64);
    private static final Color START_BTN_BG = new Color(24, 34, 52);
    private static final Color ACCENT_CYAN = new Color(45, 212, 235);
    private static final Color ACCENT_GOLD = new Color(250, 182, 45);
    private static final Color ACCENT_GREEN = new Color(46, 213, 115);
    private static final Color ACCENT_RED = new Color(235, 59, 90);
    private static final Color TEXT_WHITE = new Color(240, 244, 250);
    private static final Color TEXT_MUTED = new Color(135, 150, 175);

    private final List<PlasmaWindow> windows = new ArrayList<>();
    private PlasmaWindow activeWindow = null;

    // Drag-Zustand für Fenstertitel
    private PlasmaWindow draggingTitleWindow = null;
    private int dragWinOffsetX = 0;
    private int dragWinOffsetY = 0;

    // Start-Menü Zustand
    private boolean startMenuOpen = false;

    // Einzelne Fenster-Instanzen
    private final FlowboardStudioWindow flowboardWindow;
    private final DmxMixerWindow mixerWindow;
    private final AutoStudioWindow autoStudioWindow;
    private final S2lAnalyzerWindow s2lWindow;

    private int universeId = 0; // 0..15

    public PlasmaWindowManager() {
        // Standard-Fenstergrößen und -Positionen
        // Flowboard startet links oben
        this.flowboardWindow = new FlowboardStudioWindow(10, 8, 430, 318);
        // DMX Mixer startet rechts
        this.mixerWindow = new DmxMixerWindow(190, 8, 440, 318);
        // Auto Studio startet zentriert
        this.autoStudioWindow = new AutoStudioWindow(40, 16, 560, 300);
        // S2L Analyzer startet zentriert
        this.s2lWindow = new S2lAnalyzerWindow(100, 24, 440, 280);

        // Mixer & Flowboard standardmäßig sichtbar, Flowboard im Vordergrund
        this.autoStudioWindow.setVisible(false);
        this.s2lWindow.setVisible(false);

        this.windows.add(mixerWindow);
        this.windows.add(flowboardWindow);
        this.activeWindow = flowboardWindow;
    }

    public FlowboardStudioWindow flowboardWindow() {
        return flowboardWindow;
    }

    public DmxMixerWindow mixerWindow() {
        return mixerWindow;
    }

    public AutoStudioWindow autoStudioWindow() {
        return autoStudioWindow;
    }

    public S2lAnalyzerWindow s2lWindow() {
        return s2lWindow;
    }

    public int universeId() {
        return universeId;
    }

    public void setUniverseId(int universeId) {
        this.universeId = Math.clamp(universeId, 0, DmxUniverse.MAX_UNIVERSES - 1);
    }

    public void bringToFront(PlasmaWindow window) {
        if (window == null) return;
        windows.remove(window);
        windows.add(window);
        window.setVisible(true);
        window.setMinimized(false);
        this.activeWindow = window;
    }

    public void resetLayout() {
        flowboardWindow.toggleMaximize(WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        if (flowboardWindow.isMaximized()) flowboardWindow.toggleMaximize(WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        flowboardWindow.moveTo(10, 8, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        flowboardWindow.setSize(430, 318);
        flowboardWindow.setVisible(true);
        flowboardWindow.setMinimized(false);

        mixerWindow.toggleMaximize(WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        if (mixerWindow.isMaximized()) mixerWindow.toggleMaximize(WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        mixerWindow.moveTo(190, 8, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        mixerWindow.setSize(440, 318);
        mixerWindow.setVisible(true);
        mixerWindow.setMinimized(false);

        autoStudioWindow.moveTo(40, 16, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        autoStudioWindow.setSize(560, 300);
        autoStudioWindow.setVisible(false);

        s2lWindow.moveTo(100, 24, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
        s2lWindow.setSize(440, 280);
        s2lWindow.setVisible(false);

        bringToFront(mixerWindow);
        bringToFront(flowboardWindow);
    }

    public void drawDesktop(Graphics2D g, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        // 1. KDE Breeze Dark Wallpaper Hintergrund
        g.setPaint(new GradientPaint(0, 0, new Color(14, 18, 28), WORKSPACE_WIDTH, WORKSPACE_HEIGHT, new Color(6, 9, 15)));
        g.fillRect(0, 0, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);

        // Subtile moderne Plasma-Linien / Grid im Hintergrund
        g.setColor(new Color(22, 28, 42, 60));
        for (int y = 0; y < WORKSPACE_HEIGHT; y += 24) {
            g.drawLine(0, y, WORKSPACE_WIDTH, y);
        }

        // 2. Fenster in Z-Reihenfolge zeichnen (von unten nach oben)
        for (PlasmaWindow w : windows) {
            w.drawWindow(g, w == activeWindow, plugin, universe, cursorX, cursorY);
        }

        // 3. Start-Menü Popup zeichnen, falls geöffnet
        if (startMenuOpen) {
            drawStartMenu(g, cursorX, cursorY);
        }

        // 4. Plasma Taskleiste unten zeichnen
        drawPlasmaTaskbar(g, plugin, universe, cursorX, cursorY);
    }

    private void drawPlasmaTaskbar(Graphics2D g, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        int tbY = WORKSPACE_HEIGHT;

        // Taskbar Hintergrund & Rahmen
        g.setColor(TASKBAR_BG);
        g.fillRect(0, tbY, WORKSPACE_WIDTH, TASKBAR_HEIGHT);
        g.setColor(TASKBAR_BORDER);
        g.drawLine(0, tbY, WORKSPACE_WIDTH, tbY);

        // [✦ ADMX] Start Button
        Rectangle startBtn = new Rectangle(4, tbY + 2, 72, TASKBAR_HEIGHT - 4);
        boolean startHov = startBtn.contains(cursorX, cursorY);
        g.setColor(startMenuOpen ? ACCENT_CYAN : (startHov ? new Color(38, 50, 75) : START_BTN_BG));
        g.fillRoundRect(startBtn.x, startBtn.y, startBtn.width, startBtn.height, 4, 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(startMenuOpen ? Color.BLACK : ACCENT_CYAN);
        drawCenteredString(g, "✦ ADMX", startBtn);

        // Fenster-Buttons in der Mitte der Taskleiste
        int tabX = 82;
        int tabW = 105;
        for (PlasmaWindow w : windows) {
            if (!w.isVisible()) continue;
            Rectangle tabR = new Rectangle(tabX, tbY + 2, tabW, TASKBAR_HEIGHT - 4);
            boolean isCurActive = (w == activeWindow && !w.isMinimized());
            boolean tabHov = tabR.contains(cursorX, cursorY);

            g.setColor(isCurActive ? new Color(32, 45, 68) : (tabHov ? new Color(24, 32, 48) : new Color(18, 23, 34)));
            g.fillRoundRect(tabR.x, tabR.y, tabR.width, tabR.height, 4, 4);

            if (isCurActive) {
                // Aktiver leuchtender Akzentstrich unten am Reiter
                g.setColor(w.accentColor());
                g.fillRect(tabR.x + 4, tabR.y + tabR.height - 2, tabR.width - 8, 2);
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g.setColor(isCurActive ? TEXT_WHITE : TEXT_MUTED);
            String label = (w.icon() != null ? w.icon() + " " : "") + truncate(w.title(), 12);
            drawCenteredString(g, label, tabR);

            tabX += tabW + 4;
        }

        // System-Tray rechts: Live-BPM, Universum & Blackout (DBO)
        BeatSnapshot beat = plugin != null ? plugin.getAudioDspManager().getSnapshot() : BeatSnapshot.idle();

        // 1. Live-BPM Badge
        Rectangle bpmR = new Rectangle(WORKSPACE_WIDTH - 210, tbY + 3, 68, TASKBAR_HEIGHT - 6);
        boolean kickPulse = beat.isKick();
        g.setColor(kickPulse ? new Color(30, 80, 50) : new Color(18, 24, 36));
        g.fillRoundRect(bpmR.x, bpmR.y, bpmR.width, bpmR.height, 4, 4);
        g.setColor(kickPulse ? ACCENT_GREEN : BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(bpmR.x, bpmR.y, bpmR.width, bpmR.height, 4, 4);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(kickPulse ? Color.WHITE : ACCENT_GREEN);
        drawCenteredString(g, String.format(Locale.ROOT, "%.1f BPM", beat.bpm()), bpmR);

        // 2. Universum & Merge Badge
        if (universe != null) {
            Rectangle uniR = new Rectangle(WORKSPACE_WIDTH - 138, tbY + 3, 58, TASKBAR_HEIGHT - 6);
            g.setColor(new Color(18, 24, 36));
            g.fillRoundRect(uniR.x, uniR.y, uniR.width, uniR.height, 4, 4);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            g.setColor(ACCENT_CYAN);
            drawCenteredString(g, "U" + universe.displayNumber() + " [" + universe.mergeMode().displayName().substring(0, 3) + "]", uniR);
        }

        // 3. Blackout Not-Aus Taster (DBO)
        Rectangle dboR = new Rectangle(WORKSPACE_WIDTH - 76, tbY + 2, 72, TASKBAR_HEIGHT - 4);
        boolean dbo = universe != null && universe.isBlackout();
        g.setColor(dbo ? ACCENT_RED : new Color(68, 22, 32));
        g.fillRoundRect(dboR.x, dboR.y, dboR.width, dboR.height, 4, 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(TEXT_WHITE);
        drawCenteredString(g, dbo ? "🚨 DBO ON" : "⬛ DBO", dboR);
    }

    private void drawStartMenu(Graphics2D g, int cursorX, int cursorY) {
        int smW = 180;
        int smH = 145;
        int smX = 4;
        int smY = WORKSPACE_HEIGHT - smH - 2;

        // Schatten & Hintergrund
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(smX + 3, smY + 3, smW, smH, 8, 8);
        g.setColor(new Color(18, 24, 36, 250));
        g.fillRoundRect(smX, smY, smW, smH, 6, 6);
        g.setColor(ACCENT_CYAN);
        g.drawRoundRect(smX, smY, smW, smH, 6, 6);

        // Header
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(ACCENT_GOLD);
        g.drawString("✦ ADMX APPLICATION LAUNCHER", smX + 10, smY + 16);

        // Einträge
        String[] menuItems = {
                "⚡ Node Flowboard Studio",
                "🎛️ DMX Mixer Pult",
                "🧠 Auto-Show Studio",
                "🎵 Sound-to-Light DSP",
                "🔄 Layout zurücksetzen"
        };

        for (int i = 0; i < menuItems.length; i++) {
            Rectangle itemR = new Rectangle(smX + 4, smY + 24 + i * 23, smW - 8, 20);
            boolean hov = itemR.contains(cursorX, cursorY);
            if (hov) {
                g.setColor(new Color(36, 50, 75));
                g.fillRoundRect(itemR.x, itemR.y, itemR.width, itemR.height, 4, 4);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g.setColor(hov ? TEXT_WHITE : TEXT_MUTED);
            g.drawString(menuItems[i], itemR.x + 8, itemR.y + 14);
        }
    }

    public boolean handleClick(CommandSender sender, int x, int y, boolean right, AdmxPlugin plugin, DmxUniverse universe) {
        // 1. Klick in Taskleiste (Y >= WORKSPACE_HEIGHT)
        if (y >= WORKSPACE_HEIGHT) {
            handleTaskbarClick(x, y, plugin, universe);
            return true;
        }

        // 2. Klick bei geöffnetem Start-Menü
        if (startMenuOpen) {
            handleStartMenuClick(x, y);
            this.startMenuOpen = false;
            return true;
        }

        // 3. Klick in Fenster (von oben nach unten in Z-Stack durchgehen)
        for (int i = windows.size() - 1; i >= 0; i--) {
            PlasmaWindow w = windows.get(i);
            if (!w.contains(x, y)) continue;

            // Fenster nach ganz oben holen
            bringToFront(w);

            // Klick in Titelleiste Controls?
            if (w.titleBarRect().contains(x, y)) {
                if (w.closeBtnRect().contains(x, y)) {
                    w.setVisible(false);
                    return true;
                }
                if (w.maxBtnRect().contains(x, y)) {
                    w.toggleMaximize(WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
                    return true;
                }
                if (w.minBtnRect().contains(x, y)) {
                    w.setMinimized(true);
                    return true;
                }
                return true;
            }

            // Klick in Fensterinhalt weiterleiten
            Rectangle cr = w.contentRect();
            if (cr.contains(x, y)) {
                return w.onContentClick(x - cr.x, y - cr.y, right, plugin, universe);
            }
            return true;
        }

        return false;
    }

    public boolean handleDrag(CommandSender sender, int x, int y, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe) {
        if (phase == DragPhase.START) {
            // Prüfe, ob Titelleiste eines Fensters angefasst wird
            for (int i = windows.size() - 1; i >= 0; i--) {
                PlasmaWindow w = windows.get(i);
                if (w.isVisible() && !w.isMinimized() && w.titleBarRect().contains(x, y)) {
                    if (!w.minBtnRect().contains(x, y) && !w.maxBtnRect().contains(x, y) && !w.closeBtnRect().contains(x, y)) {
                        bringToFront(w);
                        this.draggingTitleWindow = w;
                        this.dragWinOffsetX = x - w.x();
                        this.dragWinOffsetY = y - w.y();
                        return true;
                    }
                }
            }
        }

        if (this.draggingTitleWindow != null) {
            if (phase == DragPhase.MOVE) {
                this.draggingTitleWindow.moveTo(x - dragWinOffsetX, y - dragWinOffsetY, WORKSPACE_WIDTH, WORKSPACE_HEIGHT);
                return true;
            }
            if (phase == DragPhase.END) {
                this.draggingTitleWindow = null;
                return true;
            }
        }

        // Dragging im aktiven Fensterinhalt weiterleiten
        if (activeWindow != null && activeWindow.isVisible() && !activeWindow.isMinimized()) {
            Rectangle cr = activeWindow.contentRect();
            return activeWindow.onContentDrag(x - cr.x, y - cr.y, right, phase, plugin, universe);
        }

        return false;
    }

    public boolean handleScroll(CommandSender sender, int x, int y, int delta, AdmxPlugin plugin, DmxUniverse universe) {
        if (activeWindow != null && activeWindow.isVisible() && !activeWindow.isMinimized() && activeWindow.contains(x, y)) {
            Rectangle cr = activeWindow.contentRect();
            return activeWindow.onContentScroll(x - cr.x, y - cr.y, delta, plugin, universe);
        }
        return false;
    }

    public void handleKey(CommandSender sender, String key, AdmxPlugin plugin, DmxUniverse universe) {
        if ("ESCAPE".equalsIgnoreCase(key) || "ESC".equalsIgnoreCase(key)) {
            this.startMenuOpen = false;
        }
        if (activeWindow != null) {
            activeWindow.onContentKey(key, plugin, universe);
        }
    }

    private void handleTaskbarClick(int x, int y, AdmxPlugin plugin, DmxUniverse universe) {
        int tbY = WORKSPACE_HEIGHT;

        // [✦ ADMX] Start Button
        if (new Rectangle(4, tbY + 2, 72, TASKBAR_HEIGHT - 4).contains(x, y)) {
            this.startMenuOpen = !this.startMenuOpen;
            return;
        }

        // Universum & Merge Badge
        if (new Rectangle(WORKSPACE_WIDTH - 138, tbY + 3, 58, TASKBAR_HEIGHT - 6).contains(x, y)) {
            this.universeId = (this.universeId + 1) % DmxUniverse.MAX_UNIVERSES;
            return;
        }

        // Blackout Not-Aus Taster rechts
        if (new Rectangle(WORKSPACE_WIDTH - 76, tbY + 2, 72, TASKBAR_HEIGHT - 4).contains(x, y)) {
            if (universe != null) {
                universe.setBlackout(!universe.isBlackout());
            }
            return;
        }

        // Fenster-Tabs in Taskleiste
        int tabX = 82;
        int tabW = 105;
        for (PlasmaWindow w : windows) {
            if (!w.isVisible()) continue;
            Rectangle tabR = new Rectangle(tabX, tbY + 2, tabW, TASKBAR_HEIGHT - 4);
            if (tabR.contains(x, y)) {
                if (w == activeWindow && !w.isMinimized()) {
                    w.setMinimized(true);
                } else {
                    bringToFront(w);
                }
                return;
            }
            tabX += tabW + 4;
        }
    }

    private void handleStartMenuClick(int x, int y) {
        int smW = 180;
        int smH = 145;
        int smX = 4;
        int smY = WORKSPACE_HEIGHT - smH - 2;

        Rectangle smRect = new Rectangle(smX, smY, smW, smH);
        if (!smRect.contains(x, y)) return;

        int idx = (y - (smY + 24)) / 23;
        switch (idx) {
            case 0 -> bringToFront(flowboardWindow);
            case 1 -> bringToFront(mixerWindow);
            case 2 -> {
                if (!windows.contains(autoStudioWindow)) windows.add(autoStudioWindow);
                bringToFront(autoStudioWindow);
            }
            case 3 -> {
                if (!windows.contains(s2lWindow)) windows.add(s2lWindow);
                bringToFront(s2lWindow);
            }
            case 4 -> resetLayout();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void drawCenteredString(Graphics2D g, String text, Rectangle rect) {
        FontMetrics fm = g.getFontMetrics();
        int cx = rect.x + (rect.width - fm.stringWidth(text)) / 2;
        int cy = rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, cx, cy);
    }

    private static final Color BREEZE_BORDER_INACTIVE = new Color(42, 52, 70);
}
