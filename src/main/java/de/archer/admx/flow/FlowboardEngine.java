package de.archer.admx.flow;

import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Echtzeit-Ausführungs-Engine für das visuelle Flowboard-System.
 * <p>
 * Berechnet Frame für Frame den Ablauf der Lichtshow, moduliert Zielgruppen anhand
 * der aktiven Nodes, feuert Signalverbindungen (Trigger on End / Sync Start) und
 * garantiert die Einhaltung aller Sicherheitsregeln (Fotosensibilität, Null-Zeiger-Schutz).
 */
public final class FlowboardEngine {

    public static final class ActiveNodeInstance {
        private final FlowNode node;
        private int localTick;

        public ActiveNodeInstance(FlowNode node) {
            this.node = node;
            this.localTick = 0;
        }

        public FlowNode node() {
            return node;
        }

        public int localTick() {
            return localTick;
        }

        public void advance() {
            localTick++;
        }
    }

    private volatile Flowboard activeBoard;
    private volatile boolean playing = false;
    private volatile int currentTick = 0;

    private final Map<Integer, ActiveNodeInstance> runningNodes = new ConcurrentHashMap<>();

    public FlowboardEngine() {
    }

    public synchronized void play(Flowboard board) {
        if (board == null) {
            return;
        }
        this.activeBoard = board;
        this.currentTick = 0;
        this.runningNodes.clear();
        this.playing = true;

        // Starte alle Root-Nodes mit StartTick == 0
        triggerRootNodesAt(0);
    }

    public synchronized void stop() {
        this.playing = false;
        this.currentTick = 0;
        this.runningNodes.clear();
    }

    public boolean isPlaying() {
        return playing;
    }

    public int currentTick() {
        return currentTick;
    }

    public Flowboard activeBoard() {
        return activeBoard;
    }

    public Map<Integer, ActiveNodeInstance> runningNodes() {
        return Collections.unmodifiableMap(runningNodes);
    }

    public boolean isNodeRunning(int nodeId) {
        return runningNodes.containsKey(nodeId);
    }

    /**
     * Wird in jedem Server-Tick (20 Hz) aufgerufen, um die Flowboard-Effekte auf die Gruppen anzuwenden.
     */
    public synchronized void tick(Map<String, FixtureGroup> groups) {
        if (!playing || activeBoard == null) {
            return;
        }

        currentTick++;

        // 1. Root-Nodes aktivieren, deren Startzeitpunkt genau jetzt erreicht ist
        triggerRootNodesAt(currentTick);

        // 2. Alle laufenden Node-Instanzen ausführen
        List<Integer> finishedNodes = new ArrayList<>();

        for (ActiveNodeInstance instance : runningNodes.values()) {
            FlowNode node = instance.node();
            int localTick = instance.localTick();
            int duration = node.durationTicks();

            float progress = Math.clamp((float) localTick / (float) duration, 0.0f, 1.0f);
            applyNodeEffect(node, progress, localTick, groups);

            instance.advance();

            if (instance.localTick() >= duration) {
                finishedNodes.add(node.nodeId());
            }
        }

        // 3. Beendete Nodes auflösen & Signalverbindungen auslösen
        for (int finishedId : finishedNodes) {
            ActiveNodeInstance finished = runningNodes.remove(finishedId);
            if (finished != null) {
                cleanupNodeEffect(finished.node(), groups);
                triggerOutgoingConnections(finished.node());
            }
        }

        // 4. Show-Ende prüfen & ggf. neu loopen
        if (currentTick >= activeBoard.durationTicks()) {
            if (activeBoard.isLoopMode()) {
                currentTick = 0;
                runningNodes.clear();
                triggerRootNodesAt(0);
            } else {
                stop();
            }
        }
    }

    private void triggerRootNodesAt(int tick) {
        if (activeBoard == null) {
            return;
        }
        for (FlowNode node : activeBoard.nodes().values()) {
            if (activeBoard.isRootNode(node.nodeId()) && node.startTick() == tick) {
                startNode(node);
            }
        }
    }

    public synchronized void startNode(FlowNode node) {
        if (node == null) {
            return;
        }
        runningNodes.put(node.nodeId(), new ActiveNodeInstance(node));

        // Alle direkt per SYNC_START verbundenen Folge-Nodes zeitgleich anstoßen
        if (activeBoard != null) {
            for (FlowConnection c : activeBoard.getOutgoingConnections(node.nodeId())) {
                if (c.type() == FlowConnection.ConnectionType.SYNC_START) {
                    FlowNode next = activeBoard.getNode(c.toNodeId());
                    if (next != null && !runningNodes.containsKey(next.nodeId())) {
                        startNode(next);
                    }
                }
            }
        }
    }

    private void triggerOutgoingConnections(FlowNode node) {
        if (activeBoard == null) {
            return;
        }
        for (FlowConnection c : activeBoard.getOutgoingConnections(node.nodeId())) {
            if (c.type() == FlowConnection.ConnectionType.TRIGGER_ON_END) {
                FlowNode next = activeBoard.getNode(c.toNodeId());
                if (next != null) {
                    startNode(next);
                }
            }
        }
    }

    private Collection<FixtureGroup> resolveTargetGroups(FlowNode node, Map<String, FixtureGroup> allGroups) {
        if (node.targetsAllGroups()) {
            return allGroups.values();
        }
        FixtureGroup g = allGroups.get(node.targetGroupId());
        return g != null ? List.of(g) : List.of();
    }

    private void applyNodeEffect(FlowNode node, float progress, int localTick, Map<String, FixtureGroup> allGroups) {
        Collection<FixtureGroup> targets = resolveTargetGroups(node, allGroups);
        if (targets.isEmpty()) {
            return;
        }

        switch (node.type()) {
            case COLOR_FADE -> {
                float targetHue = (node.param1() >= 0) ? (node.param1() % 360f) : 180f;
                for (FixtureGroup g : targets) {
                    float currentHue = g.hueDegrees();
                    float diff = ((targetHue - currentHue + 540f) % 360f) - 180f;
                    g.setHueDegrees((currentHue + diff * progress + 360f) % 360f);
                }
            }
            case COLOR_RAINBOW -> {
                float rainbowSpeed = (node.param1() > 0) ? node.param1() : 12f;
                float currentHue = (float) (((double) localTick * rainbowSpeed) % 360.0);
                for (FixtureGroup g : targets) {
                    g.setHueDegrees(currentHue);
                }
            }
            case MOVEMENT_SHAPE -> {
                MovementPattern pattern;
                try {
                    pattern = MovementPattern.valueOf(node.paramString().toUpperCase());
                } catch (Exception e) {
                    pattern = MovementPattern.CIRCLE;
                }
                float speed = (node.param1() > 0) ? node.param1() : 1.0f;
                float amplitude = (node.param2() > 0) ? node.param2() : 0.85f;
                for (FixtureGroup g : targets) {
                    g.setMovementPattern(pattern);
                    g.setMovementSpeed(speed);
                    g.setMovementAmplitude(amplitude);
                }
            }
            case DIMMER_PULSE -> {
                float maxDimmer = (node.param2() > 0) ? Math.clamp(node.param2(), 0.1f, 1.0f) : 1.0f;
                float dim = (float) Math.sin(progress * Math.PI) * maxDimmer;
                for (FixtureGroup g : targets) {
                    g.setDimmer(dim);
                }
            }
            case STROBE_BLAST -> {
                // Fotosensibilitäts-Sicherheitsregel (SEC-09): Niemals über 10 Hz!
                float hz = Math.clamp(node.param1() > 0 ? node.param1() : 8.0f, 0.0f, 10.0f);
                for (FixtureGroup g : targets) {
                    g.setStrobeHz(hz);
                }
            }
            case FOG_BURST -> {
                float fogDim = (node.param2() > 0) ? Math.clamp(node.param2(), 0.1f, 1.0f) : 1.0f;
                for (FixtureGroup g : targets) {
                    g.setDimmer(fogDim);
                }
            }
            case FLAME_SHOT -> {
                for (FixtureGroup g : targets) {
                    g.setDimmer(1.0f);
                }
            }
            case BLACKOUT_DROP -> {
                for (FixtureGroup g : targets) {
                    g.setDimmer(0.0f);
                    g.setStrobeHz(0.0f);
                }
            }
            case COMPOSITE_CUE -> {
                MovementPattern pattern = MovementPattern.STATIC;
                if (node.paramString() != null && !node.paramString().isBlank()) {
                    try {
                        pattern = MovementPattern.valueOf(node.paramString().trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (Exception ignored) {
                        pattern = MovementPattern.CIRCLE;
                    }
                }
                float speed = (node.param1() > 0f) ? node.param1() : 1.0f;
                float dim = (node.param2() >= 0f) ? Math.clamp(node.param2(), 0.0f, 1.0f) : 1.0f;
                for (FixtureGroup g : targets) {
                    g.setMovementPattern(pattern);
                    g.setMovementSpeed(speed);
                    g.setDimmer(dim);
                }
            }
        }
    }

    private void cleanupNodeEffect(FlowNode node, Map<String, FixtureGroup> allGroups) {
        Collection<FixtureGroup> targets = resolveTargetGroups(node, allGroups);
        switch (node.type()) {
            case STROBE_BLAST, COMPOSITE_CUE -> {
                for (FixtureGroup g : targets) {
                    g.setStrobeHz(0.0f);
                }
            }
            case DIMMER_PULSE, FOG_BURST, FLAME_SHOT -> {
                // Nach Ablauf des Impulses wird der Dimmer sanft zurückgenommen
                for (FixtureGroup g : targets) {
                    if (node.type() == FlowNodeType.FOG_BURST || node.type() == FlowNodeType.FLAME_SHOT) {
                        g.setDimmer(0.0f);
                    }
                }
            }
            default -> {
            }
        }
    }
}
