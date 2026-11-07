package de.archer.admx.control;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Szenen-Programmier-Ebene (Cue-Recorder & Sequencer):
 * Erlaubt das Aufzeichnen von Szenen-Cues (Snapshots aller Gerätegruppen)
 * sowie das direkte Abrufen einzelner Cues oder das flüssige Abspielen als geloopte Show-Sequenz
 * unter Berücksichtigung der pro Cue gespeicherten {@code holdTicks} und {@code fadeTicks} (BUG-08).
 */
public final class SceneTimelineManager {

    public static final int MAX_CUES = 4;

    private final Map<Integer, SceneCue> cues = new ConcurrentHashMap<>();
    private volatile int[] cachedSortedSlots = new int[0];
    private boolean playingSequence = false;
    private int activeCueIndex = 0;
    private long sequenceTick = 0;

    public SceneTimelineManager() {
    }

    public void loadCues(Collection<SceneCue> loaded) {
        cues.clear();
        for (SceneCue cue : loaded) {
            cues.put(cue.cueIndex(), cue);
        }
        rebuildSortedSlots();
    }

    private void rebuildSortedSlots() {
        cachedSortedSlots = cues.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    public Collection<SceneCue> allCues() {
        return cues.values();
    }

    public SceneCue getCue(int cueIndex) {
        return cues.get(cueIndex);
    }

    public boolean hasCue(int cueIndex) {
        return cues.containsKey(cueIndex);
    }

    public boolean isPlayingSequence() {
        return playingSequence;
    }

    public int activeCueIndex() {
        return activeCueIndex;
    }

    /**
     * Zeichnet den aktuellen Zustand aller übergebenen Gruppen in den angegebenen Cue-Slot (0..3) auf.
     */
    public SceneCue recordCue(int cueIndex, String name, Collection<FixtureGroup> groups) {
        Map<String, SceneCue.GroupSnapshot> snaps = new HashMap<>();
        for (FixtureGroup g : groups) {
            snaps.put(g.groupId(), g.createSnapshot());
        }
        SceneCue cue = new SceneCue(cueIndex, name, 25, 60, snaps);
        cues.put(cueIndex, cue);
        rebuildSortedSlots();
        return cue;
    }

    /**
     * Lädt einen gespeicherten Cue sofort live in alle Gruppen.
     */
    public boolean recallCue(int cueIndex, Map<String, FixtureGroup> groupsById) {
        SceneCue cue = cues.get(cueIndex);
        if (cue == null) {
            return false;
        }
        this.activeCueIndex = cueIndex;
        this.playingSequence = false;
        for (Map.Entry<String, SceneCue.GroupSnapshot> entry : cue.groupSnapshots().entrySet()) {
            FixtureGroup group = groupsById.get(entry.getKey());
            if (group != null) {
                group.applySnapshot(entry.getValue());
                if (group.controlMode() == ControlMode.AUTO_SHOW || group.controlMode() == ControlMode.SCENE_PLAYBACK) {
                    group.setControlMode(ControlMode.MANUAL_LIVE);
                }
            }
        }
        return true;
    }

    public void togglePlayback(Collection<FixtureGroup> groups) {
        this.playingSequence = !this.playingSequence;
        this.sequenceTick = 0;
        if (this.playingSequence) {
            for (FixtureGroup g : groups) {
                g.setControlMode(ControlMode.SCENE_PLAYBACK);
            }
        } else {
            for (FixtureGroup g : groups) {
                if (g.controlMode() == ControlMode.SCENE_PLAYBACK) {
                    g.setControlMode(ControlMode.MANUAL_LIVE);
                }
            }
        }
    }

    /**
     * Aktualisiert bei laufendem Sequencer pro Tick die weiche Interpolation zwischen den aufgezeichneten Cues
     * anhand der in jedem {@link SceneCue} gespeicherten {@code holdTicks} und {@code fadeTicks} (BUG-08).
     */
    public void tickSequence(Map<String, FixtureGroup> groupsById) {
        int[] available = this.cachedSortedSlots;
        if (!playingSequence || available.length == 0) {
            return;
        }

        if (available.length == 1) {
            SceneCue only = cues.get(available[0]);
            if (only == null) return;
            activeCueIndex = only.cueIndex();
            for (var entry : only.groupSnapshots().entrySet()) {
                FixtureGroup g = groupsById.get(entry.getKey());
                if (g != null && g.controlMode() == ControlMode.SCENE_PLAYBACK) {
                    g.applySnapshot(entry.getValue());
                }
            }
            return;
        }

        // Berechne Gesamtdauer des Zyklus aus den individuellen holdTicks + fadeTicks jedes Cues
        long totalCycle = 0;
        int[] stepDurations = new int[available.length];
        int[] fadeDurations = new int[available.length];
        for (int i = 0; i < available.length; i++) {
            SceneCue c = cues.get(available[i]);
            int hold = (c != null) ? Math.max(10, c.holdTicks()) : 55;
            int fade = (c != null) ? Math.max(5, c.fadeTicks()) : 25;
            fadeDurations[i] = fade;
            stepDurations[i] = hold + fade;
            totalCycle += stepDurations[i];
        }

        if (totalCycle <= 0) {
            return;
        }

        long posInCycle = sequenceTick % totalCycle;
        int idxA = 0;
        long tickInStep = posInCycle;
        for (int i = 0; i < available.length; i++) {
            if (tickInStep < stepDurations[i]) {
                idxA = i;
                break;
            }
            tickInStep -= stepDurations[i];
        }

        int idxB = (idxA + 1) % available.length;
        int stepDuration = stepDurations[idxA];
        int fadeDuration = fadeDurations[idxA];

        SceneCue cueA = cues.get(available[idxA]);
        SceneCue cueB = cues.get(available[idxB]);
        if (cueA == null || cueB == null) {
            return;
        }
        activeCueIndex = cueA.cueIndex();

        float blend = 0f;
        int holdTicks = stepDuration - fadeDuration;
        if (tickInStep >= holdTicks && fadeDuration > 0) {
            blend = (tickInStep - holdTicks) / (float) fadeDuration;
        }

        for (FixtureGroup group : groupsById.values()) {
            if (group.controlMode() != ControlMode.SCENE_PLAYBACK) {
                continue;
            }
            SceneCue.GroupSnapshot snapA = cueA.groupSnapshots().get(group.groupId());
            SceneCue.GroupSnapshot snapB = cueB.groupSnapshots().get(group.groupId());
            if (snapA != null && snapB != null) {
                group.applySnapshot(SceneCue.GroupSnapshot.interpolate(snapA, snapB, blend));
            } else if (snapA != null) {
                group.applySnapshot(snapA);
            }
        }

        sequenceTick++;
    }
}
