package de.archer.admx.db;

import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.control.SceneCue;
import de.archer.admx.control.SceneTimelineManager;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdmxDatabaseAndControlTest {

    @TempDir
    File tempDir;

    @Test
    void testSqliteWalDatabaseGroupsAndSceneCuesIntegrity() {
        try (AdmxDatabase db = new AdmxDatabase(tempDir)) {
            Map<String, FixtureGroup> groups = db.loadOrSeedGroups();
            assertEquals(6, groups.size(), "Must seed 6 default stage groups");

            FixtureGroup front = groups.get("front_truss");
            assertNotNull(front);
            front.setDimmer(0.42f);
            front.setHueDegrees(120f);
            front.setPanDegrees(55f);
            front.setMovementPattern(MovementPattern.FIGURE_8);
            db.saveGroup(front);

            // Neu laden und Datenintegrität prüfen
            Map<String, FixtureGroup> reloaded = db.loadOrSeedGroups();
            FixtureGroup reloadedFront = reloaded.get("front_truss");
            assertEquals(0.42f, reloadedFront.dimmer(), 1e-4f);
            assertEquals(120f, reloadedFront.hueDegrees(), 1e-4f);
            assertEquals(55f, reloadedFront.panDegrees(), 1e-4f);
            assertEquals(MovementPattern.FIGURE_8, reloadedFront.movementPattern());

            // Szenen-Cue speichern & laden
            SceneTimelineManager timeline = new SceneTimelineManager();
            SceneCue cue0 = timeline.recordCue(0, "Opening Scene", reloaded.values());
            db.saveSceneCue(cue0);

            List<SceneCue> loadedCues = db.loadSceneCues();
            assertEquals(1, loadedCues.size());
            assertEquals("Opening Scene", loadedCues.getFirst().name());
            assertEquals(0.42f, loadedCues.getFirst().groupSnapshots().get("front_truss").dimmer(), 1e-4f);
        }

        // CRC32-Integritätssignatur muss Änderungen an Position/Gruppe sofort erkennen
        UUID id = UUID.randomUUID();
        long crc1 = AdmxDatabase.computeFixtureCrc(id, "front_truss", 0, "world", 10.5, 64.0, -20.5, "MOVING_HEAD_SPOT");
        long crc2 = AdmxDatabase.computeFixtureCrc(id, "back_truss", 0, "world", 10.5, 64.0, -20.5, "MOVING_HEAD_SPOT");
        assertNotEquals(crc1, crc2, "CRC32 must detect group reassignment");
    }

    @Test
    void testUniqueMemberPhaseFanningAndSceneSequencer() {
        Map<String, FixtureGroup> groups = Map.of(
                "front_truss", FixtureGroup.createDefaultGroups().getFirst()
        );
        FixtureGroup g = groups.get("front_truss");
        g.setControlMode(ControlMode.MANUAL_LIVE);
        g.setMovementPattern(MovementPattern.FAN_SPREAD);
        g.setMovementAmplitude(0.8f);
        g.setPhaseSpread(1.0f);

        // Zwei unterschiedliche Geräte (#0 und #3 von 4) in derselben Gruppe müssen unterschiedliche Pan-Winkel erhalten
        FixtureState member0 = g.computeMemberState(FixtureType.MOVING_HEAD_SPOT, 0, 4, 20L);
        FixtureState member3 = g.computeMemberState(FixtureType.MOVING_HEAD_SPOT, 3, 4, 20L);
        assertNotEquals(member0.panRadians(), member3.panRadians(), 1e-3f,
                "Unique fixtures inside the same group must be fanned/phased individually");

        // Szenen-Sequencer Interpolation zwischen Cue 1 und Cue 2 testen
        SceneTimelineManager timeline = new SceneTimelineManager();
        g.setDimmer(0.2f);
        g.setPanDegrees(-60f);
        timeline.recordCue(0, "Cue 1", groups.values());

        g.setDimmer(1.0f);
        g.setPanDegrees(60f);
        timeline.recordCue(1, "Cue 2", groups.values());

        timeline.togglePlayback(groups.values());
        assertTrue(timeline.isPlayingSequence());
        assertEquals(ControlMode.SCENE_PLAYBACK, g.controlMode());

        for (int i = 0; i < 70; i++) {
            timeline.tickSequence(groups);
        }
        // Während des Crossfades (Ticks 55..80) muss der Dimmer zwischen 0.2 und 1.0 interpoliert sein
        assertTrue(g.dimmer() > 0.25f && g.dimmer() < 0.99f, "Sequencer must smoothly crossfade between cues");
    }

    @Test
    void testSecurityAuditFixesCrcLocaleAndSnapshotValidation() {
        UUID fixtureId = UUID.randomUUID();
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMAN);
            long crcGerman = AdmxDatabase.computeFixtureCrc(fixtureId, "front_truss", 1, "world", 12.3456, 65.0, -8.75, "MOVING_HEAD_SPOT");
            Locale.setDefault(Locale.US);
            long crcUs = AdmxDatabase.computeFixtureCrc(fixtureId, "front_truss", 1, "world", 12.3456, 65.0, -8.75, "MOVING_HEAD_SPOT");
            assertEquals(crcUs, crcGerman, "SEC-08: CRC32 must be invariant across JVM Locales");
        } finally {
            Locale.setDefault(prev);
        }

        // SEC-08 & SEC-09: applySnapshot muss ungültige/manipulierte Werte und Strobe > 10 Hz strikt klemmen
        FixtureGroup group = FixtureGroup.createDefaultGroups().getFirst();
        SceneCue.GroupSnapshot corruptedSnap = new SceneCue.GroupSnapshot(
                "front_truss",
                999f, -500f, 400f, 500f, 50f, Float.NaN, -5f, 10f,
                null, 99f, -3f, 8f
        );
        group.applySnapshot(corruptedSnap);
        assertEquals(1.0f, group.dimmer(), 1e-4f);
        assertEquals(-180f, group.panDegrees(), 1e-4f);
        assertEquals(90f, group.tiltDegrees(), 1e-4f);
        assertEquals(70f, group.zoomDegrees(), 1e-4f);
        assertEquals(FixtureGroup.MAX_SAFE_STROBE_HZ, group.strobeHz(), 1e-4f, "SEC-09: Strobe must be capped at 10 Hz");
        assertNotNull(group.movementPattern());

        // PERF-03: Nebelmaschinen dürfen bei aktivem Gruppen-Strobe niemals auf Dimmer 0 blinken
        for (long tick = 0; tick < 10; tick++) {
            FixtureState fogState = group.computeMemberState(FixtureType.FOG_MACHINE_HAZER, 0, 1, tick);
            assertTrue(fogState.isIlluminating(), "PERF-03: Fog machines must ignore strobe flashing");
        }

        // PERF-05 & SEC-06: Geschachtelte GroupKeys müssen das Wurzel-Handle korrekt extrahieren
        RenderGroup.GroupKey nested = new RenderGroup.GroupKey(
                new RenderGroup.GroupKey(fixtureId, "beam"),
                "face_0"
        );
        assertEquals(fixtureId, RenderGroup.extractRootHandle(nested));
    }
}
