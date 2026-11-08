package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AudioDspManager;
import de.archer.admx.audio.AudioDspManager.AudioSourceType;
import de.archer.admx.audio.BeatDetector;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
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
 * Live 24-Band FFT Audio-Spektrum-Analysator &amp; Sound-to-Light Control Panel
 * als KDE Plasma Fenster. Bietet manuelle BPM-Eingabe (Tap-Tempo / Preset 146 BPM)
 * und Source-Umschaltung (Boiler / Loopback / Demo).
 */
public final class S2lAnalyzerWindow extends PlasmaWindow {

    private long lastTapMs = 0;
    private long prevTapInterval = 0;

    public S2lAnalyzerWindow(int x, int y, int width, int height) {
        super("s2l_analyzer", "Sound-2-Light Spectrum & BPM Studio", "🎵", new Color(165, 94, 234), x, y, width, height);
    }

    @Override
    public void drawContent(Graphics2D g, Rectangle c, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        g.setColor(new Color(11, 15, 24));
        g.fillRect(c.x, c.y, c.width, c.height);

        if (plugin == null) return;
        AudioDspManager audio = plugin.getAudioDspManager();
        BeatSnapshot beat = audio.getSnapshot();
        float[] bands = audio.getSpectrumAnalyzer().currentBands();

        // 1. Obere Status- & Preset-Leiste
        g.setColor(new Color(18, 24, 38));
        g.fillRect(c.x, c.y, c.width, 24);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawLine(c.x, c.y + 24, c.x + c.width, c.y + 24);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(ACCENT_GOLD);
        g.drawString("AUDIO SOURCE: " + audio.getActiveSource().sourceName(), c.x + 8, c.y + 16);

        // Umschalter für Audioquelle rechts
        Rectangle srcBtn = new Rectangle(c.x + c.width - 90, c.y + 3, 84, 18);
        drawSmallButton(g, srcBtn, "SRC WECHSELN", new Color(36, 48, 72), TEXT_WHITE);

        // 2. 24-Band FFT Balkenspektrogramm
        int specX = c.x + 12;
        int specY = c.y + 32;
        int specW = c.width - 24;
        int specH = 100;

        g.setColor(new Color(7, 9, 15));
        g.fillRoundRect(specX, specY, specW, specH, 4, 4);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(specX, specY, specW, specH, 4, 4);

        int barCount = Math.min(24, bands.length);
        int gap = 2;
        int barW = Math.max(2, (specW - (barCount + 1) * gap) / barCount);

        for (int i = 0; i < barCount; i++) {
            float lvl = Math.clamp(bands[i], 0.0f, 1.0f);
            int bx = specX + gap + i * (barW + gap);
            int bh = Math.round(lvl * (specH - 8));

            Color barColor;
            if (i < 4) {
                barColor = new Color(245, 60, 60); // Sub-bass
            } else if (i < 8) {
                barColor = new Color(250, 160, 40); // Bass
            } else if (i < 16) {
                barColor = new Color(46, 213, 115); // Mids
            } else {
                barColor = new Color(45, 212, 235); // Highs
            }

            if (bh > 0) {
                g.setColor(barColor);
                g.fillRoundRect(bx, specY + specH - 4 - bh, barW, bh, 2, 2);
            }
        }

        // 3. Kick & Drop LED-Indikatoren
        int ledY = specY + specH + 8;
        boolean isKick = beat.isKick();
        boolean isDrop = beat.dropImpact();

        // Kick LED
        g.setColor(isKick ? Color.RED : new Color(60, 15, 20));
        g.fillOval(specX, ledY + 2, 14, 14);
        g.setColor(TEXT_WHITE);
        g.drawOval(specX, ledY + 2, 14, 14);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(isKick ? Color.WHITE : TEXT_MUTED);
        g.drawString("KICK ONSET", specX + 18, ledY + 13);

        // Drop LED
        g.setColor(isDrop ? Color.YELLOW : new Color(50, 45, 10));
        g.fillOval(specX + 105, ledY + 2, 14, 14);
        g.setColor(TEXT_WHITE);
        g.drawOval(specX + 105, ledY + 2, 14, 14);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(isDrop ? Color.WHITE : TEXT_MUTED);
        g.drawString("DROP IMPACT", specX + 123, ledY + 13);

        // Genre & Confidence
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(ACCENT_CYAN);
        String genreInfo = String.format(Locale.ROOT, "Genre: %s (%d%%)", beat.genre().displayName(), Math.round(beat.genreConfidence() * 100f));
        g.drawString(genreInfo, specX + specW - 145, ledY + 13);

        // 4. Manuelle BPM-Justierung & Tap-Tempo (Nutzerwunsch!)
        int ctrlY = ledY + 26;
        g.setColor(new Color(16, 22, 34));
        g.fillRoundRect(specX, ctrlY, specW, 36, 4, 4);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(specX, ctrlY, specW, 36, 4, 4);

        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        g.setColor(ACCENT_GOLD);
        String bpmStr = String.format(Locale.ROOT, "%.1f BPM", beat.bpm());
        g.drawString(bpmStr, specX + 10, ctrlY + 23);

        // Buttons: [-5] [-1] [TAP] [+1] [+5] [146 BPM LOCK]
        drawSmallButton(g, new Rectangle(specX + 85, ctrlY + 6, 26, 24), "-5", new Color(32, 40, 60), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(specX + 114, ctrlY + 6, 26, 24), "-1", new Color(32, 40, 60), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(specX + 143, ctrlY + 6, 38, 24), "TAP", new Color(130, 45, 170), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(specX + 184, ctrlY + 6, 26, 24), "+1", new Color(32, 40, 60), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(specX + 213, ctrlY + 6, 26, 24), "+5", new Color(32, 40, 60), TEXT_WHITE);
        drawSmallButton(g, new Rectangle(specX + 245, ctrlY + 6, 88, 24), "🎯 146 BPM LOCK", new Color(220, 60, 45), TEXT_WHITE);
    }

    @Override
    public boolean onContentClick(int localX, int localY, boolean right, AdmxPlugin plugin, DmxUniverse universe) {
        if (plugin == null) return false;
        AudioDspManager audio = plugin.getAudioDspManager();
        BeatDetector detector = audio.getBeatDetector();
        Rectangle c = contentRect();
        int specX = c.x + 12;
        int specW = c.width - 24;
        int ctrlY = c.y + 32 + 100 + 8 + 26;

        // Audio Source Switch
        if (new Rectangle(c.x + c.width - 90, c.y + 3, 84, 18).contains(c.x + localX, c.y + localY)) {
            AudioSourceType[] types = AudioSourceType.values();
            int nextIdx = (audio.getActiveSource().ordinal() + 1) % types.length;
            audio.setActiveSource(types[nextIdx]);
            return true;
        }

        // BPM Controls
        int vx = c.x + localX;
        int vy = c.y + localY;

        if (new Rectangle(specX + 85, ctrlY + 6, 26, 24).contains(vx, vy)) {
            detector.setManualBpm(detector.currentBpm() - 5.0);
            return true;
        }
        if (new Rectangle(specX + 114, ctrlY + 6, 26, 24).contains(vx, vy)) {
            detector.setManualBpm(detector.currentBpm() - 1.0);
            return true;
        }
        if (new Rectangle(specX + 143, ctrlY + 6, 38, 24).contains(vx, vy)) {
            // Tap Tempo
            long now = System.currentTimeMillis();
            if (lastTapMs > 0) {
                long interval = now - lastTapMs;
                if (interval >= 200 && interval <= 2000) {
                    double tappedBpm = 60000.0 / interval;
                    if (prevTapInterval > 0) {
                        tappedBpm = 60000.0 / ((interval + prevTapInterval) * 0.5);
                    }
                    detector.setManualBpm(tappedBpm);
                    prevTapInterval = interval;
                }
            }
            lastTapMs = now;
            return true;
        }
        if (new Rectangle(specX + 184, ctrlY + 6, 26, 24).contains(vx, vy)) {
            detector.setManualBpm(detector.currentBpm() + 1.0);
            return true;
        }
        if (new Rectangle(specX + 213, ctrlY + 6, 26, 24).contains(vx, vy)) {
            detector.setManualBpm(detector.currentBpm() + 5.0);
            return true;
        }
        if (new Rectangle(specX + 245, ctrlY + 6, 88, 24).contains(vx, vy)) {
            // Exact 146.0 BPM for the user's Schranz track
            detector.setManualBpm(146.0);
            return true;
        }

        return false;
    }

    @Override
    public boolean onContentDrag(int localX, int localY, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe) {
        return false;
    }

    @Override
    public boolean onContentScroll(int localX, int localY, int delta, AdmxPlugin plugin, DmxUniverse universe) {
        if (plugin == null || delta == 0) return false;
        BeatDetector detector = plugin.getAudioDspManager().getBeatDetector();
        double change = delta > 0 ? 0.5 : -0.5;
        detector.setManualBpm(detector.currentBpm() + change);
        return true;
    }
}
