package de.archer.admx.boiler.plasma;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.audio.AutoShowProfile.ChoreoStyle;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.audio.MusicGenre;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.dmx.DmxUniverse;
import net.somewhatcity.boiler.api.util.DragPhase;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vollwertiges, interaktives Node-Based Flowboard Studio für das Boiler-Desktop-OS.
 * <p>
 * Erlaubt das freie Platzieren von modularen Sequence Bricks (Choreografie-Bausteine)
 * auf einer gerasterten Arbeitsfläche und verbindet diese mit geschwungenen,
 * leuchtenden Bezier-Kabeln. Synchronisiert Tempo, Strobe und Bewegungs-Kurven
 * (inkl. {@link MovementPattern#HARD_SNAP_STACCATO}) direkt mit dem Audiosignal oder DMX-Takt.
 */
public final class FlowboardStudioWindow extends PlasmaWindow {

    public enum BrickType {
        INTRO_STOMP("Intro Stomp", new Color(245, 175, 45), MovementPattern.FAN_SPREAD, 4, 1.0f, 0.70f, 0.0f, true, false),
        PIANO_RAP("Piano Rap Groove", new Color(45, 195, 235), MovementPattern.PAN_SWEEP, 16, 0.85f, 0.55f, 0.0f, false, false),
        SNARE_BUILDUP("Snare Buildup", new Color(235, 135, 45), MovementPattern.TILT_WAVE, 8, 1.30f, 0.85f, 6.0f, false, false),
        BRASS_DROP_SNAP("Brass Drop (Hard Snap)", new Color(235, 45, 80), MovementPattern.HARD_SNAP_STACCATO, 16, 1.50f, 1.00f, 10.0f, true, true),
        VOCAL_BREAKDOWN("Vocal Breakdown", new Color(135, 120, 245), MovementPattern.TILT_WAVE, 8, 0.50f, 0.40f, 0.0f, false, false),
        ANTHEM_CHORUS("Anthem Chorus", new Color(46, 213, 115), MovementPattern.CIRCLE, 16, 1.40f, 0.90f, 8.0f, true, true),
        LASER_MATRIX("Laser Rain Matrix", new Color(0, 240, 255), MovementPattern.FIGURE_8, 8, 1.60f, 0.95f, 10.0f, false, false),
        CLIFFHANGER("Cliffhanger (Kick-Cut)", new Color(255, 71, 87), MovementPattern.CLIFFHANGER_FREEZE, 1, 0.0f, 0.0f, 0.0f, true, false),
        CINEMATIC_DRUMS("Cinematic Drums (Stomp Snap)", new Color(165, 94, 234), MovementPattern.CINEMATIC_STOMP_SNAP, 8, 1.20f, 0.95f, 3.0f, true, true);

        final String displayName;
        final Color color;
        final MovementPattern pattern;
        final int defaultBars;
        final float speed;
        final float amplitude;
        final float strobeHz;
        final boolean blinderPulse;
        final boolean flameBurst;

        BrickType(String displayName, Color color, MovementPattern pattern, int defaultBars,
                  float speed, float amplitude, float strobeHz, boolean blinderPulse, boolean flameBurst) {
            this.displayName = displayName;
            this.color = color;
            this.pattern = pattern;
            this.defaultBars = defaultBars;
            this.speed = speed;
            this.amplitude = amplitude;
            this.strobeHz = strobeHz;
            this.blinderPulse = blinderPulse;
            this.flameBurst = flameBurst;
        }
    }

    public static final class FlowNode {
        final String id;
        String name;
        BrickType type;
        int x;
        int y;
        int width = 126;
        int height = 66;
        int bars;
        MovementPattern pattern;
        float speed;
        float amplitude;
        float strobeHz;
        Color primaryColor;
        Color secondaryColor;
        int dimmer = 100;
        boolean flameEnabled;

        FlowNode(String id, String name, BrickType type, int x, int y, int bars) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.x = x;
            this.y = y;
            this.bars = bars;
            this.pattern = type.pattern;
            this.speed = type.speed;
            this.amplitude = type.amplitude;
            this.strobeHz = type.strobeHz;
            this.primaryColor = type.color;
            this.secondaryColor = new Color(255 - type.color.getRed(), 255 - type.color.getGreen(), type.color.getBlue());
            this.flameEnabled = type.flameBurst;
        }

        public Point2D inPinLoc() {
            return new Point2D.Double(x, y + height * 0.5);
        }

        public Point2D outPinLoc(int pinIndex) {
            if (pinIndex == 0) {
                // Pin 0: On End / Next
                return new Point2D.Double(x + width, y + height * 0.35);
            } else {
                // Pin 1: On Drop / Kick
                return new Point2D.Double(x + width, y + height * 0.70);
            }
        }

        public boolean containsInPin(int px, int py) {
            Point2D pt = inPinLoc();
            return pt.distance(px, py) <= 7.0;
        }

        public int containsOutPin(int px, int py) {
            if (outPinLoc(0).distance(px, py) <= 7.0) return 0;
            if (outPinLoc(1).distance(px, py) <= 7.0) return 1;
            return -1;
        }
    }

    public static final class FlowCable {
        final String fromNodeId;
        final int fromPin;
        final String toNodeId;
        final int toPin;

        public FlowCable(String fromNodeId, int fromPin, String toNodeId, int toPin) {
            this.fromNodeId = fromNodeId;
            this.fromPin = fromPin;
            this.toNodeId = toNodeId;
            this.toPin = toPin;
        }
    }

    private final List<FlowNode> nodes = new ArrayList<>();
    private final List<FlowCable> cables = new ArrayList<>();

    // Interaktiver Drag-Zustand
    private FlowNode draggedNode = null;
    private int dragNodeOffsetX = 0;
    private int dragNodeOffsetY = 0;

    // Kabel-Ziehen
    private FlowNode wiringFromNode = null;
    private int wiringFromPin = -1;
    private int wireCurrentX = 0;
    private int wireCurrentY = 0;

    // Sequencer Playback
    private boolean isPlaying = false;
    private int activeNodeIndex = 0;
    private long playbackStartTick = 0;
    private int animPulsePhase = 0;

    public FlowboardStudioWindow(int x, int y, int width, int height) {
        super("flowboard_studio", "Node Flowboard Studio", "⚡", new Color(45, 212, 235), x, y, width, height);
        generateDefaultSongFlow();
    }

    public void generateDefaultSongFlow() {
        this.nodes.clear();
        this.cables.clear();

        // Vollständiger 166-Takte Ablauf (146 BPM Schranz-Remix) inklusive Cliffhanger & Cinematic Drums
        FlowNode n1 = new FlowNode("n1", "1. Intro Rap", BrickType.INTRO_STOMP, 18, 45, 4);
        FlowNode n2 = new FlowNode("n2", "2. Verse Rap", BrickType.PIANO_RAP, 168, 45, 16);
        FlowNode n3 = new FlowNode("n3", "3. Snare Riser", BrickType.SNARE_BUILDUP, 318, 45, 4);
        FlowNode n4 = new FlowNode("n4", "4. Cliffhanger", BrickType.CLIFFHANGER, 468, 45, 1);
        FlowNode n5 = new FlowNode("n5", "5. Drop Snap 1", BrickType.BRASS_DROP_SNAP, 18, 145, 16);
        FlowNode n6 = new FlowNode("n6", "6. Cinematic Stomps", BrickType.CINEMATIC_DRUMS, 168, 145, 8);
        FlowNode n7 = new FlowNode("n7", "7. Vocal Bridge", BrickType.VOCAL_BREAKDOWN, 318, 145, 16);
        FlowNode n8 = new FlowNode("n8", "8. Climax Drop 2", BrickType.BRASS_DROP_SNAP, 468, 145, 20);

        this.nodes.add(n1);
        this.nodes.add(n2);
        this.nodes.add(n3);
        this.nodes.add(n4);
        this.nodes.add(n5);
        this.nodes.add(n6);
        this.nodes.add(n7);
        this.nodes.add(n8);

        this.cables.add(new FlowCable("n1", 0, "n2", 0));
        this.cables.add(new FlowCable("n2", 0, "n3", 0));
        this.cables.add(new FlowCable("n3", 0, "n4", 0));
        this.cables.add(new FlowCable("n4", 0, "n5", 0));
        this.cables.add(new FlowCable("n5", 0, "n6", 0));
        this.cables.add(new FlowCable("n6", 0, "n7", 0));
        this.cables.add(new FlowCable("n7", 0, "n8", 0));
        this.cables.add(new FlowCable("n8", 0, "n5", 0)); // Loop back to drop 1
    }

    private FlowNode findNode(String id) {
        for (FlowNode n : nodes) {
            if (n.id.equals(id)) return n;
        }
        return null;
    }

    @Override
    public void drawContent(Graphics2D g, Rectangle contentRect, AdmxPlugin plugin, DmxUniverse universe, int cursorX, int cursorY) {
        animPulsePhase = (animPulsePhase + 1) % 1000;

        // 1. Raster-Hintergrund (Subtiles 20px Flowboard-Grid)
        g.setColor(new Color(11, 14, 22));
        g.fillRect(contentRect.x, contentRect.y, contentRect.width, contentRect.height);

        g.setColor(new Color(22, 28, 42));
        for (int gx = contentRect.x; gx < contentRect.x + contentRect.width; gx += 20) {
            g.drawLine(gx, contentRect.y, gx, contentRect.y + contentRect.height);
        }
        for (int gy = contentRect.y; gy < contentRect.y + contentRect.height; gy += 20) {
            g.drawLine(contentRect.x, gy, contentRect.x + contentRect.width, gy);
        }

        // 2. Toolbar oben im Flowboard
        int tbH = 26;
        g.setColor(new Color(18, 23, 34, 230));
        g.fillRect(contentRect.x, contentRect.y, contentRect.width, tbH);
        g.setColor(BREEZE_BORDER_INACTIVE);
        g.drawLine(contentRect.x, contentRect.y + tbH, contentRect.x + contentRect.width, contentRect.y + tbH);

        // Toolbar-Buttons
        drawSmallButton(g, new Rectangle(contentRect.x + 6, contentRect.y + 3, 62, 20),
                isPlaying ? "⏹ STOP" : "▶ PLAY",
                isPlaying ? new Color(220, 50, 70) : new Color(34, 165, 85), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(contentRect.x + 72, contentRect.y + 3, 68, 20),
                "+ BRICK", new Color(38, 50, 74), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(contentRect.x + 144, contentRect.y + 3, 90, 20),
                "⚡ 146 BPM AUTO", new Color(95, 52, 148), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(contentRect.x + 238, contentRect.y + 3, 56, 20),
                "💾 SAVE", new Color(180, 105, 20), TEXT_WHITE);

        drawSmallButton(g, new Rectangle(contentRect.x + 298, contentRect.y + 3, 50, 20),
                "🧹 CLR", new Color(75, 28, 36), TEXT_WHITE);

        // Live-BPM & Bar-Status in Toolbar
        BeatSnapshot beat = plugin != null ? plugin.getAudioDspManager().getSnapshot() : BeatSnapshot.idle();
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
        g.setColor(ACCENT_CYAN);
        String status = String.format(Locale.ROOT, "BPM: %.1f | Bar %d.%d | Nodes: %d",
                beat.bpm(), beat.barCount() + 1, beat.beatInBar() + 1, nodes.size());
        g.drawString(status, contentRect.x + contentRect.width - 195, contentRect.y + 16);

        // 3. Bezier-Kabel zeichnen
        for (FlowCable c : cables) {
            FlowNode from = findNode(c.fromNodeId);
            FlowNode to = findNode(c.toNodeId);
            if (from == null || to == null) continue;

            Point2D p1 = from.outPinLoc(c.fromPin);
            Point2D p2 = to.inPinLoc();
            int sx = contentRect.x + (int) Math.round(p1.getX());
            int sy = contentRect.y + (int) Math.round(p1.getY());
            int ex = contentRect.x + (int) Math.round(p2.getX());
            int ey = contentRect.y + (int) Math.round(p2.getY());

            drawCurvedBezierCable(g, sx, sy, ex, ey, from.type.color, isPlaying && nodes.indexOf(from) == activeNodeIndex);
        }

        // Live gezogenes Kabel bei Mausbewegung
        if (wiringFromNode != null) {
            Point2D p1 = wiringFromNode.outPinLoc(wiringFromPin);
            int sx = contentRect.x + (int) Math.round(p1.getX());
            int sy = contentRect.y + (int) Math.round(p1.getY());
            drawCurvedBezierCable(g, sx, sy, wireCurrentX, wireCurrentY, wiringFromNode.type.color, true);
        }

        // 4. Nodes (Sequence Bricks) zeichnen
        for (int i = 0; i < nodes.size(); i++) {
            FlowNode node = nodes.get(i);
            int nx = contentRect.x + node.x;
            int ny = contentRect.y + node.y;
            boolean isActiveNode = isPlaying && (i == activeNodeIndex);

            // Node-Kasten Drop-Shadow
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRoundRect(nx + 3, ny + 3, node.width, node.height, 8, 8);

            // Node-Hintergrund
            g.setColor(new Color(20, 26, 40, 240));
            g.fillRoundRect(nx, ny, node.width, node.height, 8, 8);

            // Node-Rahmen (Pulsierend, wenn gerade aktiv)
            if (isActiveNode) {
                g.setColor(ACCENT_GOLD);
                g.setStroke(new BasicStroke(2.0f));
            } else {
                g.setColor(node.type.color.darker());
                g.setStroke(new BasicStroke(1.0f));
            }
            g.drawRoundRect(nx, ny, node.width, node.height, 8, 8);
            g.setStroke(new BasicStroke(1.0f));

            // Node Header-Banner
            g.setColor(node.type.color);
            g.fillRoundRect(nx + 1, ny + 1, node.width - 2, 16, 7, 7);
            g.fillRect(nx + 1, ny + 10, node.width - 2, 8);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g.setColor(new Color(10, 14, 20));
            g.drawString(node.name, nx + 6, ny + 12);

            // Bars-Badge
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            g.setColor(new Color(15, 20, 30));
            String barBadge = node.bars + " Bars";
            g.drawString(barBadge, nx + node.width - 38, ny + 12);

            // Node Body: Bewegungsmuster & Spezifikationen
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
            g.setColor(TEXT_WHITE);
            g.drawString("Pattern: " + truncate(node.pattern.displayName(), 14), nx + 8, ny + 32);

            g.setColor(TEXT_MUTED);
            String strbText = node.strobeHz > 0.5f ? String.format(Locale.ROOT, "%.0fHz", node.strobeHz) : "OFF";
            g.drawString(String.format(Locale.ROOT, "Spd:%.1f | Strb:%s", node.speed, strbText), nx + 8, ny + 44);

            if (node.flameEnabled) {
                g.setColor(new Color(255, 120, 20));
                g.drawString("🔥 PYRO DROP", nx + 8, ny + 56);
            } else {
                g.setColor(TEXT_MUTED);
                g.drawString("Dim: " + node.dimmer + "%", nx + 8, ny + 56);
            }

            // Pins zeichnen:
            // Input Pin links (Kreis)
            Point2D inPt = node.inPinLoc();
            int ipx = contentRect.x + (int) Math.round(inPt.getX());
            int ipy = contentRect.y + (int) Math.round(inPt.getY());
            g.setColor(ACCENT_CYAN);
            g.fillOval(ipx - 5, ipy - 5, 10, 10);
            g.setColor(TEXT_WHITE);
            g.drawOval(ipx - 5, ipy - 5, 10, 10);

            // Output Pin 0 (Next) oben rechts
            Point2D outPt0 = node.outPinLoc(0);
            int opx0 = contentRect.x + (int) Math.round(outPt0.getX());
            int opy0 = contentRect.y + (int) Math.round(outPt0.getY());
            g.setColor(node.type.color);
            g.fillOval(opx0 - 5, opy0 - 5, 10, 10);
            g.setColor(TEXT_WHITE);
            g.drawOval(opx0 - 5, opy0 - 5, 10, 10);

            // Output Pin 1 (Drop/Kick) unten rechts
            Point2D outPt1 = node.outPinLoc(1);
            int opx1 = contentRect.x + (int) Math.round(outPt1.getX());
            int opy1 = contentRect.y + (int) Math.round(outPt1.getY());
            g.setColor(ACCENT_RED);
            g.fillOval(opx1 - 5, opy1 - 5, 10, 10);
            g.setColor(TEXT_WHITE);
            g.drawOval(opx1 - 5, opy1 - 5, 10, 10);
        }

        // Live-Playback Trigger Update
        if (isPlaying && plugin != null) {
            updatePlaybackTick(plugin);
        }
    }

    private void updatePlaybackTick(AdmxPlugin plugin) {
        if (nodes.isEmpty()) return;
        BeatSnapshot beat = plugin.getAudioDspManager().getSnapshot();
        long bar = beat.barCount();

        // Node-Wechsel basierend auf Takten (Bars)
        FlowNode cur = nodes.get(activeNodeIndex % nodes.size());
        if (bar != playbackStartTick && (bar % Math.max(1, cur.bars)) == 0) {
            playbackStartTick = bar;
            activeNodeIndex = (activeNodeIndex + 1) % nodes.size();
            applyNodeToLiveGroups(plugin, nodes.get(activeNodeIndex));
        }
    }

    private void applyNodeToLiveGroups(AdmxPlugin plugin, FlowNode node) {
        for (FixtureGroup g : plugin.getFixtureGroups().values()) {
            g.setControlMode(ControlMode.AUTO_SHOW);
            g.setMovementPattern(node.pattern);
            g.setMovementSpeed(node.speed);
            g.setMovementAmplitude(node.amplitude);
            g.setStrobeHz(Math.min(10.0f, node.strobeHz));
            g.setDimmer(node.dimmer / 100.0f);
        }
        plugin.getDatabase().saveAllGroupsAsync(plugin.getFixtureGroups().values());
    }

    private static void drawCurvedBezierCable(Graphics2D g, int x1, int y1, int x2, int y2, Color color, boolean pulse) {
        int dx = Math.abs(x2 - x1);
        int ctrlOffset = Math.max(40, dx / 2);

        CubicCurve2D curve = new CubicCurve2D.Double(
                x1, y1,
                x1 + ctrlOffset, y1,
                x2 - ctrlOffset, y2,
                x2, y2
        );

        // Äußerer Glow
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), pulse ? 120 : 60));
        g.setStroke(new BasicStroke(pulse ? 4.5f : 3.0f));
        g.draw(curve);

        // Kernkabel
        g.setColor(pulse ? Color.WHITE : color);
        g.setStroke(new BasicStroke(1.8f));
        g.draw(curve);
        g.setStroke(new BasicStroke(1.0f));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean onContentClick(int localX, int localY, boolean right, AdmxPlugin plugin, DmxUniverse universe) {
        // 1. Toolbar-Klicks
        if (localY <= 26) {
            if (new Rectangle(6, 3, 62, 20).contains(localX, localY)) {
                // Play / Stop
                this.isPlaying = !this.isPlaying;
                if (isPlaying && plugin != null && !nodes.isEmpty()) {
                    applyNodeToLiveGroups(plugin, nodes.get(activeNodeIndex));
                }
                return true;
            }
            if (new Rectangle(72, 3, 68, 20).contains(localX, localY)) {
                // Neuer Brick hinzufügen
                BrickType[] types = BrickType.values();
                BrickType chosen = types[nodes.size() % types.length];
                int nextX = 20 + ((nodes.size() % 3) * 150);
                int nextY = 45 + ((nodes.size() / 3) * 100);
                String id = "n" + (nodes.size() + 1);
                nodes.add(new FlowNode(id, (nodes.size() + 1) + ". " + chosen.displayName, chosen, nextX, nextY, chosen.defaultBars));
                return true;
            }
            if (new Rectangle(144, 3, 90, 20).contains(localX, localY)) {
                // 146 BPM Auto-Flow generieren
                generateDefaultSongFlow();
                return true;
            }
            if (new Rectangle(238, 3, 56, 20).contains(localX, localY)) {
                // Als Auto-Show Profile in Slot speichern
                if (plugin != null) {
                    AutoShowProfile profile = new AutoShowProfile(
                            "flow_show", "Flowboard 146 Schranz", 0, MusicGenre.TECHNO, ChoreoStyle.SCHRANZ_STACCATO,
                            146.0, 16, 0.04f, 0.95f, 0.20f, 0.10f, 0.85f, 1.50f,
                            38f, 195f, 0.98f,
                            MovementPattern.TILT_WAVE, MovementPattern.PAN_SWEEP, MovementPattern.FAN_SPREAD, MovementPattern.HARD_SNAP_STACCATO,
                            1.50f, 1.00f, 0.90f, 10.0f, true, true,
                            256, 4, 8, 0.99f
                    );
                    plugin.saveAutoShowProfile(profile);
                    plugin.getSmartAutoDirector().recallProfile(profile);
                }
                return true;
            }
            if (new Rectangle(298, 3, 50, 20).contains(localX, localY)) {
                // Clear
                this.nodes.clear();
                this.cables.clear();
                this.isPlaying = false;
                return true;
            }
            return true;
        }

        // 2. Rechtsklick auf Node: Parameter umschalten (Pattern durchschalten oder löschen)
        for (int i = nodes.size() - 1; i >= 0; i--) {
            FlowNode n = nodes.get(i);
            if (localX >= n.x && localX <= n.x + n.width && localY >= n.y && localY <= n.y + n.height) {
                if (right) {
                    // Pattern durchschalten
                    n.pattern = n.pattern.next();
                    if (plugin != null && isPlaying) {
                        applyNodeToLiveGroups(plugin, n);
                    }
                } else {
                    // Bars erhöhen
                    n.bars = (n.bars == 4) ? 8 : (n.bars == 8 ? 16 : 4);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onContentDrag(int localX, int localY, boolean right, DragPhase phase, AdmxPlugin plugin, DmxUniverse universe) {
        if (localY <= 26 && phase == DragPhase.START) {
            return false;
        }

        if (phase == DragPhase.START) {
            // Prüfe zuerst, ob ein Ausgangs-Pin zum Kabelziehen angeklickt wurde
            for (FlowNode n : nodes) {
                int outPin = n.containsOutPin(localX, localY);
                if (outPin >= 0) {
                    this.wiringFromNode = n;
                    this.wiringFromPin = outPin;
                    this.wireCurrentX = localX;
                    this.wireCurrentY = localY;
                    return true;
                }
            }

            // Prüfe, ob eine Node zum Verschieben angeklickt wurde
            for (int i = nodes.size() - 1; i >= 0; i--) {
                FlowNode n = nodes.get(i);
                if (localX >= n.x && localX <= n.x + n.width && localY >= n.y && localY <= n.y + n.height) {
                    this.draggedNode = n;
                    this.dragNodeOffsetX = localX - n.x;
                    this.dragNodeOffsetY = localY - n.y;
                    // Bring node to front of list
                    nodes.remove(i);
                    nodes.add(n);
                    return true;
                }
            }
            return false;
        }

        if (phase == DragPhase.MOVE) {
            if (this.wiringFromNode != null) {
                this.wireCurrentX = localX;
                this.wireCurrentY = localY;
                return true;
            }
            if (this.draggedNode != null) {
                this.draggedNode.x = Math.max(5, localX - dragNodeOffsetX);
                this.draggedNode.y = Math.max(30, localY - dragNodeOffsetY);
                return true;
            }
        }

        if (phase == DragPhase.END) {
            if (this.wiringFromNode != null) {
                // Dockt an Ziel-Node an, wenn über Input-Pin losgelassen
                for (FlowNode target : nodes) {
                    if (target != wiringFromNode && target.containsInPin(localX, localY)) {
                        cables.removeIf(c -> c.fromNodeId.equals(wiringFromNode.id) && c.fromPin == wiringFromPin);
                        cables.add(new FlowCable(wiringFromNode.id, wiringFromPin, target.id, 0));
                        break;
                    }
                }
                this.wiringFromNode = null;
                this.wiringFromPin = -1;
                return true;
            }
            this.draggedNode = null;
        }
        return false;
    }

    @Override
    public boolean onContentScroll(int localX, int localY, int delta, AdmxPlugin plugin, DmxUniverse universe) {
        // Mausrad über Node ändert Geschwindigkeit oder Strobefrequenz
        for (FlowNode n : nodes) {
            if (localX >= n.x && localX <= n.x + n.width && localY >= n.y && localY <= n.y + n.height) {
                if (localY >= n.y + 35) {
                    // Strobe regeln (0 .. 10 Hz)
                    n.strobeHz = Math.clamp(n.strobeHz + (delta > 0 ? 1.0f : -1.0f), 0.0f, 10.0f);
                } else {
                    // Speed regeln
                    n.speed = Math.clamp(n.speed + (delta > 0 ? 0.1f : -0.1f), 0.2f, 3.0f);
                }
                if (isPlaying && plugin != null) {
                    applyNodeToLiveGroups(plugin, n);
                }
                return true;
            }
        }
        return false;
    }
}
