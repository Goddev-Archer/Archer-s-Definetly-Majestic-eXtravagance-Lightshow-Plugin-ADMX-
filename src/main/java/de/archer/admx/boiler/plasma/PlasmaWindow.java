package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.util.DragPhase;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;

/**
 * Basisklasse für Fenster im KDE-Plasma-inspirierten Fenstermanager des Boiler-Displays.
 * Verwaltet Position, Größe, Titelleiste, Minimieren/Maximieren/Schließen und leitet
 * Maus- sowie Tastaturevents an den Fensterinhalt weiter.
 */
public abstract class PlasmaWindow {

    public static final int TITLE_BAR_HEIGHT = 20;

    protected static final Color BREEZE_TITLE_ACTIVE_TOP = new Color(38, 48, 68);
    protected static final Color BREEZE_TITLE_ACTIVE_BTM = new Color(24, 31, 46);
    protected static final Color BREEZE_TITLE_INACTIVE = new Color(20, 25, 36);
    protected static final Color BREEZE_WINDOW_BG = new Color(16, 20, 30, 245);
    protected static final Color BREEZE_BORDER_ACTIVE = new Color(52, 152, 219);
    protected static final Color BREEZE_BORDER_INACTIVE = new Color(42, 52, 70);
    protected static final Color TEXT_WHITE = new Color(240, 244, 250);
    protected static final Color TEXT_MUTED = new Color(140, 155, 180);
    protected static final Color ACCENT_CYAN = new Color(45, 212, 235);
    protected static final Color ACCENT_RED = new Color(235, 59, 90);
    protected static final Color ACCENT_GOLD = new Color(250, 182, 45);
    protected static final Color ACCENT_GREEN = new Color(46, 213, 115);

    private final String id;
    private String title;
    private String icon;
    private Color accentColor;

    private int x;
    private int y;
    private int width;
    private int height;

    private int normalX;
    private int normalY;
    private int normalWidth;
    private int normalHeight;

    private boolean minimized = false;
    private boolean maximized = false;
    private boolean visible = true;

    public PlasmaWindow(String id, String title, String icon, Color accentColor, int x, int y, int width, int height) {
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.accentColor = accentColor != null ? accentColor : ACCENT_CYAN;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.normalX = x;
        this.normalY = y;
        this.normalWidth = width;
        this.normalHeight = height;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String icon() {
        return icon;
    }

    public Color accentColor() {
        return accentColor;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isMinimized() {
        return minimized;
    }

    public void setMinimized(boolean minimized) {
        this.minimized = minimized;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public Rectangle bounds() {
        return new Rectangle(x, y, width, height);
    }

    public Rectangle titleBarRect() {
        return new Rectangle(x, y, width, TITLE_BAR_HEIGHT);
    }

    public Rectangle contentRect() {
        return new Rectangle(x + 2, y + TITLE_BAR_HEIGHT, Math.max(1, width - 4), Math.max(1, height - TITLE_BAR_HEIGHT - 2));
    }

    public Rectangle minBtnRect() {
        return new Rectangle(x + width - 52, y + 2, 14, 15);
    }

    public Rectangle maxBtnRect() {
        return new Rectangle(x + width - 35, y + 2, 14, 15);
    }

    public Rectangle closeBtnRect() {
        return new Rectangle(x + width - 18, y + 2, 14, 15);
    }

    public boolean contains(int px, int py) {
        if (!visible || minimized) return false;
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    public void moveTo(int newX, int newY, int workspaceW, int workspaceH) {
        if (maximized) return;
        this.x = Math.max(0, Math.min(newX, workspaceW - width));
        this.y = Math.max(0, Math.min(newY, workspaceH - height));
        this.normalX = this.x;
        this.normalY = this.y;
    }

    public void setSize(int newWidth, int newHeight) {
        if (maximized) return;
        this.width = Math.max(120, newWidth);
        this.height = Math.max(80, newHeight);
        this.normalWidth = this.width;
        this.normalHeight = this.height;
    }

    public void toggleMaximize(int workspaceW, int workspaceH) {
        if (maximized) {
            // Wiederherstellen
            this.x = normalX;
            this.y = normalY;
            this.width = normalWidth;
            this.height = normalHeight;
            this.maximized = false;
        } else {
            // Auf vollen Desktop maximieren
            this.normalX = this.x;
            this.normalY = this.y;
            this.normalWidth = this.width;
            this.normalHeight = this.height;
            this.x = 0;
            this.y = 0;
            this.width = workspaceW;
            this.height = workspaceH;
            this.maximized = true;
        }
    }

    public void drawWindow(Graphics2D g, boolean active, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        if (!visible || minimized) return;

        // Subtiler Fensterschatten
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(x + 3, y + 3, width, height, 8, 8);

        // Fenster-Körper
        g.setColor(BREEZE_WINDOW_BG);
        g.fillRoundRect(x, y, width, height, 6, 6);

        // Breeze Dark Titelleiste mit Farbverlauf
        if (active) {
            g.setPaint(new GradientPaint(x, y, BREEZE_TITLE_ACTIVE_TOP, x, y + TITLE_BAR_HEIGHT, BREEZE_TITLE_ACTIVE_BTM));
        } else {
            g.setColor(BREEZE_TITLE_INACTIVE);
        }
        g.fillRoundRect(x, y, width, TITLE_BAR_HEIGHT + 4, 6, 6);
        g.fillRect(x, y + TITLE_BAR_HEIGHT - 2, width, 4);

        // Fenster-Rahmen
        g.setColor(active ? accentColor : BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(x, y, width, height, 6, 6);

        // Titelleisten-Trennlinie
        g.setColor(active ? accentColor.darker() : BREEZE_BORDER_INACTIVE);
        g.drawLine(x, y + TITLE_BAR_HEIGHT, x + width, y + TITLE_BAR_HEIGHT);

        // Titel & Icon
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(active ? accentColor : TEXT_WHITE);
        String header = (icon != null ? icon + " " : "") + title;
        g.drawString(header, x + 8, y + 14);

        // Window Controls rechts [_] [◻] [✕]
        Rectangle minR = minBtnRect();
        Rectangle maxR = maxBtnRect();
        Rectangle clsR = closeBtnRect();

        // Minimieren
        boolean minHov = minR.contains(cursorX, cursorY);
        g.setColor(minHov ? new Color(60, 75, 105) : new Color(32, 40, 56));
        g.fillRoundRect(minR.x, minR.y, minR.width, minR.height, 3, 3);
        g.setColor(TEXT_WHITE);
        g.drawLine(minR.x + 3, minR.y + 10, minR.x + minR.width - 4, minR.y + 10);

        // Maximieren / Wiederherstellen
        boolean maxHov = maxR.contains(cursorX, cursorY);
        g.setColor(maxHov ? new Color(60, 75, 105) : new Color(32, 40, 56));
        g.fillRoundRect(maxR.x, maxR.y, maxR.width, maxR.height, 3, 3);
        g.setColor(TEXT_WHITE);
        if (maximized) {
            g.drawRect(maxR.x + 4, maxR.y + 3, 6, 6);
            g.drawRect(maxR.x + 2, maxR.y + 5, 6, 6);
        } else {
            g.drawRect(maxR.x + 3, maxR.y + 3, 7, 7);
        }

        // Schließen
        boolean clsHov = clsR.contains(cursorX, cursorY);
        g.setColor(clsHov ? ACCENT_RED : new Color(56, 32, 38));
        g.fillRoundRect(clsR.x, clsR.y, clsR.width, clsR.height, 3, 3);
        g.setColor(TEXT_WHITE);
        g.drawLine(clsR.x + 3, clsR.y + 3, clsR.x + clsR.width - 4, clsR.y + clsR.height - 4);
        g.drawLine(clsR.x + clsR.width - 4, clsR.y + 3, clsR.x + 3, clsR.y + clsR.height - 4);

        // Fenster-Inhalt zeichnen (mit sauberen Clipping-Bounds)
        Shape oldClip = g.getClip();
        Rectangle cr = contentRect();
        g.clipRect(cr.x, cr.y, cr.width, cr.height);
        try {
            drawContent(g, cr, plugin, universe, cursorX, cursorY);
        } finally {
            g.setClip(oldClip);
        }
    }

    public abstract void drawContent(Graphics2D g, Rectangle contentRect, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY);

    public abstract boolean onContentClick(int localX, int localY, boolean right, AdmxPlugin plugin, DmxUniverse universe);

    public abstract boolean onContentDrag(int localX, int localY, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe);

    public abstract boolean onContentScroll(int localX, int localY, int delta, AdmxPlugin plugin, DmxUniverse universe);

    public void onContentKey(String key, AdmxPlugin plugin, DmxUniverse universe) {
    }

    protected static void drawCenteredString(Graphics2D g, String text, Rectangle rect) {
        FontMetrics fm = g.getFontMetrics();
        int cx = rect.x + (rect.width - fm.stringWidth(text)) / 2;
        int cy = rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, cx, cy);
    }

    protected static void drawSmallButton(Graphics2D g, Rectangle r, String label, Color bg, Color textCol) {
        g.setColor(bg);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 4, 4);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(textCol);
        drawCenteredString(g, label, r);
    }
}
