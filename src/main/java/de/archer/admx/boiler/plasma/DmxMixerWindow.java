package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.dmx.DmxMergeMode;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.util.DragPhase;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Locale;

/**
 * 8-Kanal DMX512 Motorfader-Pult &amp; 2D Vector Touchpad als vollwertiges KDE Plasma Fenster.
 */
public final class DmxMixerWindow extends PlasmaWindow {

    private static final String[] SHORT_CH_ROLES = {
            "PAN", "PAN.F", "TILT", "TLT.F", "DIM", "STRB", "RED", "GRN",
            "BLU", "WHT", "ZOOM", "MACR", "GOBO", "SPD", "PRSM", "CTRL"
    };

    private static final Color[] SWATCH_COLORS = {
            new Color(255, 255, 255),
            new Color(255, 205, 135),
            new Color(255, 35, 35),
            new Color(255, 145, 0),
            new Color(0, 235, 90),
            new Color(0, 225, 255),
            new Color(35, 90, 255),
            new Color(245, 30, 220)
    };

    private int faderBank = 0; // 0..63
    private String activeDragTarget = null;

    public DmxMixerWindow(int x, int y, int width, int height) {
        super("dmx_mixer", "DMX512 Hardware Console", "🎛️", new Color(250, 182, 45), x, y, width, height);
    }

    public int faderBank() {
        return faderBank;
    }

    public void setFaderBank(int bank) {
        this.faderBank = Math.clamp(bank, 0, 63);
    }

    private Rectangle faderTrackRect(Rectangle c, int col) {
        return new Rectangle(c.x + 10 + col * 26, c.y + 44, 18, 140);
    }

    private Rectangle faderReleaseBtnRect(Rectangle c, int col) {
        return new Rectangle(c.x + 9 + col * 26, c.y + 204, 20, 15);
    }

    private Rectangle xyPadRect(Rectangle c) {
        return new Rectangle(c.x + 226, c.y + 44, 118, 118);
    }

    private Rectangle hueBarRect(Rectangle c) {
        return new Rectangle(c.x + 226, c.y + 172, 118, 14);
    }

    private Rectangle gmFaderRect(Rectangle c) {
        return new Rectangle(c.x + c.width - 42, c.y + 44, 28, 140);
    }

    @Override
    public void drawContent(Graphics2D g, Rectangle c, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        g.setColor(new Color(14, 18, 28));
        g.fillRect(c.x, c.y, c.width, c.height);

        if (universe == null) {
            g.setColor(TEXT_WHITE);
            g.drawString("Kein DMX-Universum gebunden.", c.x + 20, c.y + 40);
            return;
        }

        // Header: Bank-Info & Universum
        int startCh = faderBank * 8 + 1;
        int endCh = startCh + 7;
        int fixtureNum = (faderBank / 2) + 1;

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(ACCENT_GOLD);
        String header = String.format(Locale.ROOT, "BANK %02d/64 (CH %03d–%03d | FIXTURE #%02d) • %s",
                faderBank + 1, startCh, endCh, fixtureNum, universe.mergeMode().displayName());
        g.drawString(header, c.x + 10, c.y + 16);

        // Merge-Mode Buttons rechts im Header
        DmxMergeMode[] modes = DmxMergeMode.values();
        String[] shortModes = {"HTP", "LTP", "PULT", "ART"};
        for (int i = 0; i < modes.length; i++) {
            Rectangle mr = new Rectangle(c.x + c.width - 150 + i * 36, c.y + 4, 33, 16);
            boolean isCur = universe.mergeMode() == modes[i];
            drawSmallButton(g, mr, shortModes[i], isCur ? new Color(140, 70, 210) : new Color(28, 36, 52), TEXT_WHITE);
        }

        // 8 Motorfader
        boolean artNetActive = universe.hasActiveArtNetInput();
        for (int col = 0; col < 8; col++) {
            int chZero = faderBank * 8 + col;
            int chNum = chZero + 1;
            int roleIdx = chZero % 16;
            int mergedVal = universe.getMergedChannel(chZero);
            int consoleVal = universe.getConsoleFader(chZero);
            int artNetVal = universe.getArtNetChannel(chZero);

            Rectangle track = faderTrackRect(c, col);
            boolean hov = track.contains(cursorX, cursorY);

            // Rolle
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            g.setColor(TEXT_MUTED);
            drawCenteredString(g, String.format(Locale.ROOT, "%03d", chNum), new Rectangle(track.x - 2, c.y + 22, track.width + 4, 10));
            g.setColor(TEXT_WHITE);
            drawCenteredString(g, SHORT_CH_ROLES[roleIdx], new Rectangle(track.x - 2, c.y + 32, track.width + 4, 10));

            // Fader-Track
            g.setColor(new Color(9, 12, 18));
            g.fillRoundRect(track.x, track.y, track.width, track.height, 4, 4);
            g.setColor(hov ? ACCENT_CYAN : BREEZE_BORDER_INACTIVE);
            g.drawRoundRect(track.x, track.y, track.width, track.height, 4, 4);

            // LED-Balken
            Color barCol = (consoleVal >= 0) ? ACCENT_GOLD : (artNetActive && artNetVal > 0 ? ACCENT_GREEN : ACCENT_CYAN);
            int fillH = Math.round((mergedVal / 255.0f) * (track.height - 6));
            if (fillH > 0) {
                g.setColor(new Color(barCol.getRed(), barCol.getGreen(), barCol.getBlue(), 160));
                g.fillRoundRect(track.x + 2, track.y + track.height - 3 - fillH, track.width - 4, fillH, 3, 3);
            }

            // Fader-Kappe
            int capY = track.y + track.height - 6 - fillH;
            g.setColor(consoleVal >= 0 ? ACCENT_GOLD : new Color(200, 210, 225));
            g.fillRoundRect(track.x, capY, track.width, 6, 2, 2);

            // Wert (0..255)
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            g.setColor(barCol);
            drawCenteredString(g, String.valueOf(mergedVal), new Rectangle(track.x - 3, c.y + 188, track.width + 6, 12));

            // OVR/AUTO Release-Button
            Rectangle relR = faderReleaseBtnRect(c, col);
            g.setColor(consoleVal >= 0 ? new Color(180, 95, 20) : new Color(26, 33, 46));
            g.fillRoundRect(relR.x, relR.y, relR.width, relR.height, 3, 3);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 7));
            g.setColor(consoleVal >= 0 ? TEXT_WHITE : TEXT_MUTED);
            drawCenteredString(g, consoleVal >= 0 ? "OVR" : "AUTO", relR);
        }

        // Bank-Navigation unter den Fadern
        drawSmallButton(g, new Rectangle(c.x + 10, c.y + 224, 46, 18), "« FIX", new Color(30, 38, 54), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(c.x + 60, c.y + 224, 42, 18), "< 8CH", new Color(30, 38, 54), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(c.x + 106, c.y + 224, 42, 18), "8CH >", new Color(30, 38, 54), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(c.x + 152, c.y + 224, 46, 18), "FIX »", new Color(30, 38, 54), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(c.x + 202, c.y + 224, 34, 18), "CLR", new Color(120, 35, 45), TEXT_WHITE);

        // 2D Pan/Tilt Pad
        Rectangle xy = xyPadRect(c);
        g.setColor(new Color(9, 12, 18));
        g.fillRoundRect(xy.x, xy.y, xy.width, xy.height, 6, 6);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(xy.x, xy.y, xy.width, xy.height, 6, 6);

        int midX = xy.x + xy.width / 2;
        int midY = xy.y + xy.height / 2;
        g.setColor(new Color(25, 34, 50));
        g.drawLine(xy.x + 2, midY, xy.x + xy.width - 2, midY);
        g.drawLine(midX, xy.y + 2, midX, xy.y + xy.height - 2);
        g.drawOval(midX - 35, midY - 35, 70, 70);

        int fixBase = (faderBank / 2) * 16;
        int panVal = universe.getMergedChannel(fixBase);
        int tiltVal = universe.getMergedChannel(fixBase + 2);
        int dotX = xy.x + Math.round((panVal / 255.0f) * xy.width);
        int dotY = xy.y + xy.height - Math.round((tiltVal / 255.0f) * xy.height);

        g.setColor(ACCENT_CYAN);
        g.drawOval(dotX - 5, dotY - 5, 10, 10);
        g.fillOval(dotX - 2, dotY - 2, 4, 4);

        // HSB Regenbogen-Farbleiste
        Rectangle hb = hueBarRect(c);
        for (int hx = 0; hx < hb.width; hx++) {
            float hue = hx / (float) hb.width;
            g.setColor(Color.getHSBColor(hue, 1.0f, 1.0f));
            g.drawLine(hb.x + hx, hb.y, hb.x + hx, hb.y + hb.height);
        }
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRect(hb.x, hb.y, hb.width, hb.height);

        // 8 Swatches unter dem Farbbalken
        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            Rectangle sr = new Rectangle(c.x + 226 + i * 15, c.y + 192, 13, 16);
            g.setColor(SWATCH_COLORS[i]);
            g.fillRoundRect(sr.x, sr.y, sr.width, sr.height, 3, 3);
            g.setColor(BREEZE_BORDER_INACTIVE);
            g.drawRoundRect(sr.x, sr.y, sr.width, sr.height, 3, 3);
        }

        // Grand Master Fader ganz rechts
        Rectangle gm = gmFaderRect(c);
        g.setColor(new Color(9, 12, 18));
        g.fillRoundRect(gm.x, gm.y, gm.width, gm.height, 4, 4);
        g.setColor(ACCENT_GOLD);
        g.drawRoundRect(gm.x, gm.y, gm.width, gm.height, 4, 4);

        int gmVal = universe.grandMaster();
        int gmFill = Math.round((gmVal / 255.0f) * (gm.height - 6));
        if (gmFill > 0) {
            g.setPaint(new GradientPaint(gm.x, gm.y, ACCENT_GOLD, gm.x, gm.y + gm.height, new Color(180, 85, 10)));
            g.fillRoundRect(gm.x + 3, gm.y + gm.height - 3 - gmFill, gm.width - 6, gmFill, 3, 3);
        }
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(ACCENT_GOLD);
        drawCenteredString(g, Math.round((gmVal / 255.0f) * 100f) + "%", new Rectangle(gm.x - 4, c.y + 188, gm.width + 8, 12));

        // Szenen-Cues & Blackout ganz rechts
        for (int q = 0; q < 4; q++) {
            Rectangle qr = new Rectangle(c.x + 354 + (q % 2) * 44, c.y + 44 + (q / 2) * 26, 40, 22);
            drawSmallButton(g, qr, "CUE " + (q + 1), new Color(42, 38, 72), TEXT_WHITE);
        }

        // Blackout Not-Aus Taster
        Rectangle dboR = new Rectangle(c.x + 354, c.y + 102, 84, 26);
        boolean dbo = universe.isBlackout();
        drawSmallButton(g, dboR, dbo ? "🚨 BLACKOUT" : "⬛ DBO", dbo ? ACCENT_RED : new Color(80, 24, 34), TEXT_WHITE);

        // Quick Strobe Buttons
        drawSmallButton(g, new Rectangle(c.x + 354, c.y + 134, 40, 18), "0 HZ", new Color(30, 40, 56), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(c.x + 398, c.y + 134, 40, 18), "10 HZ", new Color(90, 40, 60), TEXT_WHITE);
    }

    @Override
    public boolean onContentClick(int localX, int localY, boolean right, AdmxPlugin plugin, DmxUniverse universe) {
        if (universe == null) return false;
        Rectangle c = contentRect();
        int vx = c.x + localX;
        int vy = c.y + localY;
        int fixBase = (faderBank / 2) * 16;

        // Fader Release
        for (int col = 0; col < 8; col++) {
            if (faderReleaseBtnRect(c, col).contains(vx, vy)) {
                universe.releaseConsoleFader(faderBank * 8 + col);
                return true;
            }
        }

        // Swatches
        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            Rectangle sr = new Rectangle(c.x + 226 + i * 15, c.y + 192, 13, 16);
            if (sr.contains(vx, vy)) {
                Color col = SWATCH_COLORS[i];
                universe.setConsoleFader(fixBase + 4, 255); // Dimmer 100%
                universe.setConsoleFader(fixBase + 6, col.getRed());
                universe.setConsoleFader(fixBase + 7, col.getGreen());
                universe.setConsoleFader(fixBase + 8, col.getBlue());
                return true;
            }
        }

        // Bank-Navigation
        if (new Rectangle(c.x + 10, c.y + 224, 46, 18).contains(vx, vy)) {
            this.faderBank = (this.faderBank - 2 + 64) % 64;
            return true;
        }
        if (new Rectangle(c.x + 60, c.y + 224, 42, 18).contains(vx, vy)) {
            this.faderBank = (this.faderBank - 1 + 64) % 64;
            return true;
        }
        if (new Rectangle(c.x + 106, c.y + 224, 42, 18).contains(vx, vy)) {
            this.faderBank = (this.faderBank + 1) % 64;
            return true;
        }
        if (new Rectangle(c.x + 152, c.y + 224, 46, 18).contains(vx, vy)) {
            this.faderBank = (this.faderBank + 2) % 64;
            return true;
        }
        if (new Rectangle(c.x + 202, c.y + 224, 34, 18).contains(vx, vy)) {
            universe.clearAllConsoleOverrides();
            return true;
        }

        // Cues 1..4
        for (int q = 0; q < 4; q++) {
            Rectangle qr = new Rectangle(c.x + 354 + (q % 2) * 44, c.y + 44 + (q / 2) * 26, 40, 22);
            if (qr.contains(vx, vy) && plugin != null) {
                if (right) {
                    var cue = plugin.getSceneTimeline().recordCue(q, "Cue " + (q + 1), plugin.getFixtureGroups().values());
                    plugin.getDatabase().saveSceneCueAsync(cue);
                } else {
                    plugin.getSceneTimeline().recallCue(q, plugin.getFixtureGroups());
                }
                return true;
            }
        }

        // Blackout (DBO)
        if (new Rectangle(c.x + 354, c.y + 102, 84, 26).contains(vx, vy)) {
            universe.setBlackout(!universe.isBlackout());
            return true;
        }

        // Quick Strobe
        if (new Rectangle(c.x + 354, c.y + 134, 40, 18).contains(vx, vy)) {
            universe.setConsoleFader(fixBase + 5, 0);
            return true;
        }
        if (new Rectangle(c.x + 398, c.y + 134, 40, 18).contains(vx, vy)) {
            universe.setConsoleFader(fixBase + 5, 255);
            return true;
        }

        return false;
    }

    @Override
    public boolean onContentDrag(int localX, int localY, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe) {
        if (universe == null) return false;
        Rectangle c = contentRect();
        int vx = c.x + localX;
        int vy = c.y + localY;
        int fixBase = (faderBank / 2) * 16;

        if (phase == DragPhase.START) {
            for (int col = 0; col < 8; col++) {
                if (faderTrackRect(c, col).contains(vx, vy)) {
                    this.activeDragTarget = "fader:" + col;
                    break;
                }
            }
            if (xyPadRect(c).contains(vx, vy)) this.activeDragTarget = "xypad";
            if (hueBarRect(c).contains(vx, vy)) this.activeDragTarget = "huebar";
            if (gmFaderRect(c).contains(vx, vy)) this.activeDragTarget = "gm";
        }

        if (phase == DragPhase.END) {
            this.activeDragTarget = null;
            return true;
        }

        if (this.activeDragTarget != null) {
            if (activeDragTarget.startsWith("fader:")) {
                int col = Integer.parseInt(activeDragTarget.substring(6));
                Rectangle tr = faderTrackRect(c, col);
                float norm = 1.0f - Math.clamp((vy - tr.y) / (float) tr.height, 0.0f, 1.0f);
                universe.setConsoleFader(faderBank * 8 + col, Math.round(norm * 255.0f));
                return true;
            }
            if ("gm".equals(activeDragTarget)) {
                Rectangle gm = gmFaderRect(c);
                float norm = 1.0f - Math.clamp((vy - gm.y) / (float) gm.height, 0.0f, 1.0f);
                universe.setGrandMaster(Math.round(norm * 255.0f));
                return true;
            }
            if ("xypad".equals(activeDragTarget)) {
                Rectangle xy = xyPadRect(c);
                float normX = Math.clamp((vx - xy.x) / (float) xy.width, 0.0f, 1.0f);
                float normY = 1.0f - Math.clamp((vy - xy.y) / (float) xy.height, 0.0f, 1.0f);
                int pan16 = Math.round(normX * 65535.0f);
                int tilt16 = Math.round(normY * 65535.0f);
                universe.setConsoleFader(fixBase, (pan16 >> 8) & 0xFF);
                universe.setConsoleFader(fixBase + 1, pan16 & 0xFF);
                universe.setConsoleFader(fixBase + 2, (tilt16 >> 8) & 0xFF);
                universe.setConsoleFader(fixBase + 3, tilt16 & 0xFF);
                return true;
            }
            if ("huebar".equals(activeDragTarget)) {
                Rectangle hb = hueBarRect(c);
                float hue = Math.clamp((vx - hb.x) / (float) hb.width, 0.0f, 1.0f);
                Color rgb = Color.getHSBColor(hue, 1.0f, 1.0f);
                universe.setConsoleFader(fixBase + 4, 255);
                universe.setConsoleFader(fixBase + 6, rgb.getRed());
                universe.setConsoleFader(fixBase + 7, rgb.getGreen());
                universe.setConsoleFader(fixBase + 8, rgb.getBlue());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onContentScroll(int localX, int localY, int delta, AdmxPlugin plugin, DmxUniverse universe) {
        if (universe == null || delta == 0) return false;
        Rectangle c = contentRect();
        int vx = c.x + localX;
        int vy = c.y + localY;
        int step = Integer.signum(delta) * 6;

        for (int col = 0; col < 8; col++) {
            Rectangle track = faderTrackRect(c, col);
            if (track.contains(vx, vy)) {
                int chZero = faderBank * 8 + col;
                int cur = universe.getMergedChannel(chZero);
                universe.setConsoleFader(chZero, Math.clamp(cur + step, 0, 255));
                return true;
            }
        }

        if (gmFaderRect(c).contains(vx, vy)) {
            universe.setGrandMaster(Math.clamp(universe.grandMaster() + step, 0, 255));
            return true;
        }

        return false;
    }
}
