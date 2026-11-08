package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.audio.AutoShowProfile.SongSection;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.audio.SmartAutoDirector;
import de.archer.admx.audio.SmartAutoDirector.DirectorMode;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.util.DragPhase;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Locale;

/**
 * Auto-Show Studio Fenster im KDE Plasma OS:
 * Song-DNA Multi-Pass Learner, 4-Phasen-Indikator und 8 Quick-Recall Slots.
 */
public final class AutoStudioWindow extends PlasmaWindow {

    public AutoStudioWindow(int x, int y, int width, int height) {
        super("auto_studio", "Auto-Show Studio & Song-DNA Learner", "🧠", new Color(46, 213, 115), x, y, width, height);
    }

    private static Rectangle quickSlotRect(Rectangle c, int slot) {
        int col = slot % 4;
        int row = slot / 4;
        int w = (c.width - 40) / 4;
        return new Rectangle(c.x + 16 + col * (w + 6), c.y + 130 + row * 44, w, 38);
    }

    @Override
    public void drawContent(Graphics2D g, Rectangle c, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        g.setColor(new Color(13, 18, 28));
        g.fillRect(c.x, c.y, c.width, c.height);

        if (plugin == null) return;
        SmartAutoDirector director = plugin.getSmartAutoDirector();
        BeatSnapshot beat = plugin.getAudioDspManager().getSnapshot();
        DirectorMode mode = director.directorMode();
        AutoShowProfile profile = director.activeProfile();
        SongSection activeSec = director.currentSection();
        int selectedSlot = director.selectedQuickSlot();

        // 1. Status- & Beat-PLL Sync Leiste
        g.setColor(new Color(20, 26, 40));
        g.fillRoundRect(c.x + 14, c.y + 8, c.width - 28, 22, 5, 5);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(c.x + 14, c.y + 8, c.width - 28, 22, 5, 5);

        Color modeCol = switch (mode) {
            case LEARNING_PASS -> ACCENT_RED;
            case LOCKED_PROFILE -> ACCENT_GREEN;
            case ADAPTIVE_LIVE -> ACCENT_CYAN;
        };
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(modeCol);
        g.drawString("STATUS: " + mode.displayName().toUpperCase(Locale.ROOT), c.x + 22, c.y + 22);

        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(TEXT_WHITE);
        String syncInfo = String.format(Locale.ROOT, "PLL: %.1f BPM | Bar %d.%d | 🛡️ BEAT-SYNC LOCKED",
                beat.bpm(), beat.barCount() + 1, beat.beatInBar() + 1);
        g.drawString(syncInfo, c.x + c.width - 240, c.y + 22);

        // 2. 4 Song-Sektions-Indikatoren
        SongSection[] secs = SongSection.values();
        String[] secNames = {"1. BREAKDOWN", "2. VERSE GROOVE", "3. BUILDUP", "4. PEAK DROP"};
        Color[] secCols = {ACCENT_CYAN, ACCENT_GREEN, ACCENT_GOLD, ACCENT_RED};
        int secW = (c.width - 38) / 4;
        for (int i = 0; i < secs.length; i++) {
            Rectangle sr = new Rectangle(c.x + 14 + i * (secW + 3), c.y + 34, secW, 20);
            boolean isCur = (secs[i] == activeSec);
            Color baseC = secCols[i];
            g.setColor(isCur ? new Color(baseC.getRed(), baseC.getGreen(), baseC.getBlue(), 200) : new Color(22, 28, 42));
            g.fillRoundRect(sr.x, sr.y, sr.width, sr.height, 4, 4);
            g.setColor(isCur ? TEXT_WHITE : BREEZE_BORDER_INACTIVE);
            g.drawRoundRect(sr.x, sr.y, sr.width, sr.height, 4, 4);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            g.setColor(isCur ? Color.BLACK : TEXT_MUTED);
            drawCenteredString(g, secNames[i], sr);
        }

        // 3. Multi-Pass Lern-Fortschrittsbalken (0..100%)
        int confPct = Math.round(profile.confidence() * 100f);
        Rectangle progR = new Rectangle(c.x + 14, c.y + 58, c.width - 28, 18);
        g.setColor(new Color(9, 12, 18));
        g.fillRoundRect(progR.x, progR.y, progR.width, progR.height, 4, 4);
        int fillW = Math.round((confPct / 100.0f) * (progR.width - 4));
        if (fillW > 0) {
            g.setColor(mode == DirectorMode.LEARNING_PASS ? ACCENT_GOLD : ACCENT_GREEN);
            g.fillRoundRect(progR.x + 2, progR.y + 2, fillW, progR.height - 4, 3, 3);
        }
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawRoundRect(progR.x, progR.y, progR.width, progR.height, 4, 4);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
        g.setColor(TEXT_WHITE);
        String dnaText = String.format(Locale.ROOT, "DNA Reife: %d%% (Pass #%d | %d Beats | Drops: %d | Stil: %s)",
                confPct, profile.completedPasses() + 1, profile.learnedBeats(), profile.detectedDrops(), profile.choreoStyle().displayName());
        drawCenteredString(g, dnaText, progR);

        // 4. Steuer-Buttons
        int btnW = (c.width - 38) / 4;
        drawSmallButton(g, new Rectangle(c.x + 14, c.y + 80, btnW, 20),
                mode == DirectorMode.LEARNING_PASS ? "● RESET" : "● LEARN START",
                mode == DirectorMode.LEARNING_PASS ? ACCENT_RED : new Color(28, 120, 75), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(c.x + 14 + (btnW + 3), c.y + 80, btnW, 20),
                "💾 SAVE #" + (selectedSlot + 1), new Color(175, 110, 20), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(c.x + 14 + (btnW + 3) * 2, c.y + 80, btnW, 20),
                "🎲 STIL WECHSELN", new Color(95, 52, 148), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(c.x + 14 + (btnW + 3) * 3, c.y + 80, btnW, 20),
                "⚡ ADAPTIV FREI", mode == DirectorMode.ADAPTIVE_LIVE ? ACCENT_CYAN.darker() : new Color(34, 46, 68), TEXT_WHITE);

        // 5. Quick-Recall Slots
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        g.setColor(TEXT_MUTED);
        g.drawString("QUICK-RECALL SLOTS (Links = Abrufen | Rechts = Profil hier speichern):", c.x + 16, c.y + 118);

        for (int slot = 0; slot < AutoShowProfile.MAX_QUICK_SLOTS; slot++) {
            Rectangle sr = quickSlotRect(c, slot);
            String slotId = String.format(Locale.ROOT, "show_%02d", slot + 1);
            AutoShowProfile saved = plugin.getAutoShowProfile(slotId);
            boolean isRunning = (mode == DirectorMode.LOCKED_PROFILE && slotId.equalsIgnoreCase(profile.profileId()));
            boolean isSel = (slot == selectedSlot);

            g.setColor(isRunning ? new Color(22, 96, 64) : (saved != null ? new Color(24, 32, 48) : new Color(16, 20, 28)));
            g.fillRoundRect(sr.x, sr.y, sr.width, sr.height, 4, 4);
            g.setColor(isRunning ? ACCENT_GREEN : (isSel ? ACCENT_GOLD : BREEZE_BORDER_INACTIVE));
            g.drawRoundRect(sr.x, sr.y, sr.width, sr.height, 4, 4);

            if (saved != null) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                g.setColor(isRunning ? ACCENT_GREEN : ACCENT_CYAN);
                g.drawString("#" + (slot + 1) + " " + truncate(saved.displayName(), 11), sr.x + 4, sr.y + 13);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 7));
                g.setColor(TEXT_WHITE);
                g.drawString(String.format(Locale.ROOT, "%.0f BPM • %d%%", saved.referenceBpm(), Math.round(saved.confidence() * 100f)), sr.x + 4, sr.y + 24);
                g.setColor(TEXT_MUTED);
                g.drawString(saved.profileId(), sr.x + 4, sr.y + 33);
            } else {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
                g.setColor(TEXT_MUTED);
                drawCenteredString(g, "Slot #" + (slot + 1) + " [Leer]", sr);
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean onContentClick(int localX, int localY, boolean right, AdmxPlugin plugin, DmxUniverse universe) {
        if (plugin == null) return false;
        Rectangle c = contentRect();
        int vx = c.x + localX;
        int vy = c.y + localY;
        SmartAutoDirector director = plugin.getSmartAutoDirector();
        int selectedSlot = director.selectedQuickSlot();
        int btnW = (c.width - 38) / 4;

        // Button 1: Learn Start / Reset
        if (new Rectangle(c.x + 14, c.y + 80, btnW, 20).contains(vx, vy)) {
            setAllGroupsAutoShow(plugin);
            director.startLearning(selectedSlot, "Learned Show #" + (selectedSlot + 1));
            return true;
        }

        // Button 2: Save
        if (new Rectangle(c.x + 14 + (btnW + 3), c.y + 80, btnW, 20).contains(vx, vy)) {
            AutoShowProfile saved = director.stopLearningAndFreeze(null, selectedSlot);
            plugin.saveAutoShowProfile(saved);
            setAllGroupsAutoShow(plugin);
            return true;
        }

        // Button 3: Choreo-Style
        if (new Rectangle(c.x + 14 + (btnW + 3) * 2, c.y + 80, btnW, 20).contains(vx, vy)) {
            director.mutateChoreoStyle();
            if (director.directorMode() == DirectorMode.LOCKED_PROFILE) {
                plugin.saveAutoShowProfile(director.activeProfile());
            }
            return true;
        }

        // Button 4: Adaptive Live
        if (new Rectangle(c.x + 14 + (btnW + 3) * 3, c.y + 80, btnW, 20).contains(vx, vy)) {
            director.setAdaptiveLiveMode();
            setAllGroupsAutoShow(plugin);
            return true;
        }

        // Quick-Recall Slots
        for (int slot = 0; slot < AutoShowProfile.MAX_QUICK_SLOTS; slot++) {
            Rectangle sr = quickSlotRect(c, slot);
            if (sr.contains(vx, vy)) {
                String slotId = String.format(Locale.ROOT, "show_%02d", slot + 1);
                AutoShowProfile existing = plugin.getAutoShowProfile(slotId);
                if (right) {
                    AutoShowProfile saved = director.stopLearningAndFreeze(
                            existing != null ? existing.displayName() : ("Learned Show #" + (slot + 1)),
                            slot
                    );
                    plugin.saveAutoShowProfile(saved);
                    setAllGroupsAutoShow(plugin);
                } else {
                    director.setSelectedQuickSlot(slot);
                    if (existing != null) {
                        director.recallProfile(existing);
                        setAllGroupsAutoShow(plugin);
                    } else {
                        director.startLearning(slot, "Learned Show #" + (slot + 1));
                        setAllGroupsAutoShow(plugin);
                    }
                }
                return true;
            }
        }

        return false;
    }

    private static void setAllGroupsAutoShow(AdmxPlugin plugin) {
        for (FixtureGroup g : plugin.getFixtureGroups().values()) {
            g.setControlMode(ControlMode.AUTO_SHOW);
        }
        plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
    }

    @Override
    public boolean onContentDrag(int localX, int localY, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe) {
        return false;
    }

    @Override
    public boolean onContentScroll(int localX, int localY, int delta, AdmxPlugin plugin, DmxUniverse universe) {
        return false;
    }
}
