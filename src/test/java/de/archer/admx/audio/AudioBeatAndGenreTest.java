package de.archer.admx.audio;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AudioBeatAndGenreTest {

    @Test
    void testBandpassFilterIsolatesSubBass() {
        SubBassBandpassFilter filter = new SubBassBandpassFilter(44100.0, 35.0, 140.0);

        // 1. 60 Hz Sub-Bass Testsignal (sollte passieren)
        double bassEnergy = 0.0;
        for (int i = 0; i < 2000; i++) {
            double t = i / 44100.0;
            double in = Math.sin(2.0 * Math.PI * 60.0 * t);
            double out = filter.processSample(in);
            if (i > 500) {
                bassEnergy += out * out;
            }
        }

        filter.reset();

        // 2. 4000 Hz Hi-Hat Testsignal (muss stark unterdrückt werden)
        double highEnergy = 0.0;
        for (int i = 0; i < 2000; i++) {
            double t = i / 44100.0;
            double in = Math.sin(2.0 * Math.PI * 4000.0 * t);
            double out = filter.processSample(in);
            if (i > 500) {
                highEnergy += out * out;
            }
        }

        assertTrue(bassEnergy > 10.0, "Sub-bass should pass through filter");
        assertTrue(highEnergy < 1.0, "High frequency should be heavily attenuated");
        assertTrue(bassEnergy / highEnergy > 50.0, "Filter must have strong rejection ratio");
    }

    @Test
    void testTechnoFourOnTheFloorDetectionAndClassification() {
        BeatDetector detector = new BeatDetector(44100.0, 512);
        GenreClassifier classifier = new GenreClassifier();

        // 140 BPM = 60 / 140 = 0.4285 Sekunden pro Kick
        double intervalSec = 60.0 / 140.0;
        int samplesPerBeat = (int) (intervalSec * 44100.0);

        // Simuliere 12 Takte Techno 4-on-the-Floor
        for (int beat = 0; beat < 24; beat++) {
            double[] pcm = new double[samplesPerBeat];
            // Erzeuge Kick-Impuls zu Beginn des Schlags
            for (int i = 0; i < Math.min(600, samplesPerBeat); i++) {
                double t = i / 44100.0;
                pcm[i] = Math.sin(2.0 * Math.PI * 65.0 * t) * Math.exp(-t * 30.0);
            }

            // In Blöcken von 512 Samples an den Detektor übergeben
            for (int offset = 0; offset < samplesPerBeat; offset += 512) {
                int len = Math.min(512, samplesPerBeat - offset);
                double[] block = new double[len];
                System.arraycopy(pcm, offset, block, 0, len);
                detector.processBlock(block);
            }
        }

        double detectedBpm = detector.currentBpm();
        assertEquals(140.0, detectedBpm, 5.0, "Techno BPM should be estimated near 140");

        List<Double> intervals = detector.getRecentIntervals();
        assertFalse(intervals.isEmpty());

        GenreClassifier.ClassificationResult result = classifier.classify(detectedBpm, intervals, 0.6f);
        assertEquals(MusicGenre.TECHNO, result.genre(), "4-on-the-floor fast beat must be classified as TECHNO");
        assertTrue(result.regularityScore() > 0.70, "Techno must have high regularity score");
    }

    @Test
    void testRapHipHopSyncopatedDetectionAndClassification() {
        GenreClassifier classifier = new GenreClassifier();

        // Simuliere synkopierte Rap/Trap-Intervalle bei ~80 BPM (z. B. 0.75s, 0.375s, 1.125s, 0.75s)
        List<Double> trapIntervals = List.of(0.75, 0.375, 1.125, 0.75, 0.375, 1.125, 0.75, 0.75);

        GenreClassifier.ClassificationResult result = classifier.classify(80.0, trapIntervals, 0.7f);
        assertEquals(MusicGenre.RAP_HIPHOP, result.genre(), "Syncopated 80 BPM pattern must be classified as RAP_HIPHOP");
        assertTrue(result.regularityScore() < 0.80, "Rap must have lower regularity than Techno due to syncopation");
    }

    @Test
    void testSmartAutoDirectorStrictStrobeSafety() {
        SmartAutoDirector director = new SmartAutoDirector();
        AdmxPlugin.PlacedFixture strobeFixture = new AdmxPlugin.PlacedFixture(
                UUID.randomUUID(),
                "blinders",
                0,
                null,
                0.0f,
                false,
                FixtureType.STROBE_BAR,
                MovingHeadProfile.defaultBlockbenchModel(true)
        );

        // Teste alle Genres und Phasen auf Einhaltung von SEC-09 (strobeHz <= 10.0 Hz)
        for (MusicGenre genre : MusicGenre.values()) {
            for (int step = 0; step < 20; step++) {
                double phase = step / 20.0;
                BeatSnapshot snap = new BeatSnapshot(175.0, phase, 0.9f, true, 0.9f, genre, 0.9f, 4L, 3, true);
                FixtureState state = director.computeState(strobeFixture, 4, step, snap);

                assertTrue(state.strobeHz() <= 10.0f, "Strobe frequency MUST NEVER exceed 10.0 Hz (SEC-09) for genre " + genre);
            }
        }
    }

    @Test
    void testSmartAutoDirectorTechnoVsRapDistinctBehaviors() {
        SmartAutoDirector director = new SmartAutoDirector();
        AdmxPlugin.PlacedFixture spot = new AdmxPlugin.PlacedFixture(
                UUID.randomUUID(),
                "front_truss",
                0,
                null,
                0.0f,
                false,
                FixtureType.MOVING_HEAD_SPOT,
                MovingHeadProfile.defaultBlockbenchModel(true)
        );

        BeatSnapshot technoSnap = new BeatSnapshot(140.0, 0.05, 0.95f, true, 0.85f, MusicGenre.TECHNO, 0.95f, 0L, 0, false);
        FixtureState technoState = director.computeState(spot, 4, 10, technoSnap);

        BeatSnapshot rapSnap = new BeatSnapshot(80.0, 0.05, 0.95f, true, 0.85f, MusicGenre.RAP_HIPHOP, 0.95f, 0L, 0, false);
        FixtureState rapState = director.computeState(spot, 4, 10, rapSnap);

        // Techno und Rap müssen völlig unterschiedliche Lichtbilder erzeugen!
        assertNotEquals(technoState.color(), rapState.color(), "Techno and Rap must use distinct aesthetic color palettes");
        assertNotEquals(technoState.zoomAngleDegrees(), rapState.zoomAngleDegrees(), "Rap should use wider zoom for moody wash effect");
    }

    @Test
    void testSchranzHardTechnoWithContinuousRumbleDetection() {
        // Klangkuenstler / Schranz bei 155 BPM:
        // Harter Kick mit dauerhaft dröhnendem Sub-Bass-Rumble (Verzerrung/Hallfahne)
        BeatDetector detector = new BeatDetector(44100.0, 512);
        GenreClassifier classifier = new GenreClassifier();

        double bpm = 155.0;
        double intervalSec = 60.0 / bpm;
        int samplesPerBeat = (int) (intervalSec * 44100.0);

        // Simuliere 24 Beats Schranz
        for (int beat = 0; beat < 24; beat++) {
            double[] pcm = new double[samplesPerBeat];
            for (int i = 0; i < samplesPerBeat; i++) {
                double t = i / 44100.0;
                // 1. Stetiger, verzerrter Subbass-Rumble im Hintergrund (35-70 Hz), der NIE aufhört
                double rumble = 0.35 * Math.sin(2.0 * Math.PI * 45.0 * t) + 0.15 * Math.sin(2.0 * Math.PI * 90.0 * t);

                // 2. Scharfer Kick-Punch-Transient am Beat-Anfang (Attack-Phase)
                double punch = 0.0;
                if (i < 900) {
                    double punchEnv = Math.exp(-t * 45.0);
                    punch = 0.90 * Math.sin(2.0 * Math.PI * (160.0 - t * 400.0) * t) * punchEnv;
                }

                pcm[i] = Math.clamp(rumble + punch, -1.0, 1.0);
            }

            for (int offset = 0; offset < samplesPerBeat; offset += 512) {
                int len = Math.min(512, samplesPerBeat - offset);
                double[] block = new double[len];
                System.arraycopy(pcm, offset, block, 0, len);
                detector.processBlock(block);
            }
        }

        double detectedBpm = detector.currentBpm();
        assertEquals(155.0, detectedBpm, 6.0, "Schranz 155 BPM must be accurately detected despite heavy sub-bass rumble");
        assertTrue(detector.isKickDriving(), "Kick streak must register as driving kick pattern");
        assertTrue(detector.isStrobeActive(), "Strobe must be autonomously activated during driving Schranz section");
        assertTrue(detector.strobeHz() <= 10.0f, "Strobe Hz must strictly obey SEC-09 (<= 10.0 Hz)");

        GenreClassifier.ClassificationResult result = classifier.classify(detectedBpm, detector.getRecentIntervals(), 0.8f);
        assertEquals(MusicGenre.TECHNO, result.genre(), "155 BPM hard driving kicks must classify as TECHNO");
    }

    @Test
    void testStrobeAutonomousDeactivationOnBreakdown() {
        BeatDetector detector = new BeatDetector(44100.0, 512);

        double bpm = 150.0;
        double intervalSec = 60.0 / bpm;
        int samplesPerBeat = (int) (intervalSec * 44100.0);

        // Phase 1: 16 Beats treibender Techno
        for (int beat = 0; beat < 16; beat++) {
            double[] pcm = new double[samplesPerBeat];
            for (int i = 0; i < samplesPerBeat; i++) {
                double t = i / 44100.0;
                double punch = (i < 800) ? 0.95 * Math.sin(2.0 * Math.PI * 120.0 * t) * Math.exp(-t * 40.0) : 0.0;
                pcm[i] = punch;
            }
            for (int offset = 0; offset < samplesPerBeat; offset += 512) {
                int len = Math.min(512, samplesPerBeat - offset);
                double[] block = new double[len];
                System.arraycopy(pcm, offset, block, 0, len);
                detector.processBlock(block);
            }
        }

        assertTrue(detector.isStrobeActive(), "Strobe should be active during driving kicks");

        // Phase 2: Breakdown / ruhige Synth-Fläche ohne Kicks für 3 Sekunden (130.000 Samples)
        int quietSamples = 44100 * 3;
        for (int offset = 0; offset < quietSamples; offset += 512) {
            int len = Math.min(512, quietSamples - offset);
            double[] block = new double[len];
            for (int i = 0; i < len; i++) {
                double t = (offset + i) / 44100.0;
                // Leises Pad-Signal bei 440 Hz (kein Bass-Kick)
                block[i] = 0.08 * Math.sin(2.0 * Math.PI * 440.0 * t);
            }
            detector.processBlock(block);
        }

        assertFalse(detector.isStrobeActive(), "Strobe must autonomously turn OFF during quiet breakdown");
    }

    @Test
    void testLaserAndStrobeAutoDirectorFollowsStrobeActiveFlag() {
        SmartAutoDirector director = new SmartAutoDirector();
        AdmxPlugin.PlacedFixture laser = new AdmxPlugin.PlacedFixture(
                UUID.randomUUID(),
                "lasers",
                0,
                null,
                0.0f,
                false,
                FixtureType.LASER_FAN_PROJECTOR,
                MovingHeadProfile.defaultBlockbenchModel(true)
        );
        AdmxPlugin.PlacedFixture strobe = new AdmxPlugin.PlacedFixture(
                UUID.randomUUID(),
                "strobes",
                0,
                null,
                0.0f,
                false,
                FixtureType.STROBE_BAR,
                MovingHeadProfile.defaultBlockbenchModel(true)
        );

        // Fall 1: Treibende Kicks, Strobe AKTIV (z. B. 10.0 Hz)
        BeatSnapshot snapActive = new BeatSnapshot(
                150.0, 0.02, 0.95f, true, 0.90f,
                MusicGenre.TECHNO, 0.95f, 4L, 0,
                false, true, true, 0.90f, 10.0f
        );

        FixtureState laserActive = director.computeState(laser, 2, 0, snapActive);
        FixtureState strobeActive = director.computeState(strobe, 2, 0, snapActive);

        assertTrue(laserActive.strobeHz() > 0.0f, "Laser should strobe during driving techno kick drop");
        assertTrue(laserActive.strobeHz() <= 10.0f, "Laser strobe frequency must not exceed 10.0 Hz (SEC-09)");
        assertTrue(strobeActive.strobeHz() > 0.0f, "Strobe bar should strobe during driving kick drop");
        assertTrue(strobeActive.strobeHz() <= 10.0f, "Strobe bar strobe frequency must not exceed 10.0 Hz (SEC-09)");

        // Fall 2: Breakdown, Strobe INAKTIV (0.0 Hz)
        BeatSnapshot snapInactive = new BeatSnapshot(
                150.0, 0.02, 0.95f, false, 0.30f,
                MusicGenre.TECHNO, 0.95f, 4L, 0,
                false, false, false, 0.0f, 0.0f
        );

        FixtureState laserInactive = director.computeState(laser, 2, 0, snapInactive);
        FixtureState strobeInactive = director.computeState(strobe, 2, 0, snapInactive);

        assertEquals(0.0f, laserInactive.strobeHz(), "Laser must NOT strobe during breakdown/quiet phase");
        assertEquals(0.0f, strobeInactive.strobeHz(), "Strobe bar must NOT strobe during breakdown/quiet phase");
        assertEquals(0.0f, strobeInactive.dimmer(), "Strobe bar must remain dim/off during breakdown/quiet phase");
    }

    @Test
    void testLiveRecordingWithOffbeatHiHatsAndCrowdNoise() {
        // Klangkuenstler Live-Set bei 152 BPM:
        // - Verzerrte Kicks auf dem Downbeat
        // - Laute Offbeat-Hi-Hats auf der Achtelnote (t = T / 2)
        // - Kontinuierliches Crowd-Geschrei und Raumhall (Weißes Rauschen)
        // - Niedrigerer Eingangspegel (-12 dB / 0.25 Skalierung)
        BeatDetector detector = new BeatDetector(44100.0, 512);
        GenreClassifier classifier = new GenreClassifier();

        double bpm = 152.0;
        double intervalSec = 60.0 / bpm;
        int samplesPerBeat = (int) (intervalSec * 44100.0);
        int halfBeatSamples = samplesPerBeat / 2;

        java.util.Random rnd = new java.util.Random(42);

        // Simuliere 28 Beats (ca. 11 Sekunden Live-Mitschnitt)
        for (int beat = 0; beat < 28; beat++) {
            double[] pcm = new double[samplesPerBeat];
            for (int i = 0; i < samplesPerBeat; i++) {
                double t = i / 44100.0;

                // 1. Raumhall & Crowd-Geschrei (kontinuierliches Rauschen)
                double crowdNoise = (rnd.nextDouble() * 2.0 - 1.0) * 0.04;

                // 2. Downbeat Kick (Punch + Sub-Fundamental)
                double kick = 0.0;
                if (i < 900) {
                    double kickEnv = Math.exp(-t * 35.0);
                    kick = (0.70 * Math.sin(2.0 * Math.PI * 60.0 * t) + 0.35 * Math.sin(2.0 * Math.PI * 800.0 * t)) * kickEnv;
                }

                // 3. Offbeat Hi-Hat (auf Beat-Hälfte i >= halfBeatSamples)
                double hiHat = 0.0;
                int hatOffset = i - halfBeatSamples;
                if (hatOffset >= 0 && hatOffset < 600) {
                    double hatT = hatOffset / 44100.0;
                    double hatEnv = Math.exp(-hatT * 60.0);
                    // Hochfrequenter Zischlaut bei 6-8 kHz
                    hiHat = (rnd.nextDouble() * 2.0 - 1.0) * 0.45 * hatEnv;
                }

                // Gesamtsignal bei moderater Lautstärke (-12 dBFS)
                pcm[i] = Math.clamp((kick + hiHat + crowdNoise) * 0.35, -1.0, 1.0);
            }

            for (int offset = 0; offset < samplesPerBeat; offset += 512) {
                int len = Math.min(512, samplesPerBeat - offset);
                double[] block = new double[len];
                System.arraycopy(pcm, offset, block, 0, len);
                detector.processBlock(block);
            }
        }

        double detectedBpm = detector.currentBpm();
        assertEquals(152.0, detectedBpm, 6.0, "Live-Set with offbeat hi-hats must lock to ~152 BPM via autocorrelation");

        // Genre muss felsenfest als TECHNO erkannt werden (darf wegen Offbeat-Hats niemals zu Rap oder DnB kippen!)
        GenreClassifier.ClassificationResult result = classifier.classify(
                detectedBpm,
                detector.getPeriodicityScore(),
                detector.getCurrentEnergy(),
                detector.getRecentIntervals()
        );
        assertEquals(MusicGenre.TECHNO, result.genre(), "152 BPM Live Schranz must classify as TECHNO, not Rap or DnB");
        assertTrue(detector.isKickDriving(), "Kicks must be actively driving");
        assertTrue(detector.isStrobeActive(), "Strobe must be active during live driving section");
        assertTrue(detector.strobeHz() <= 10.0f, "Strobe Hz must strictly obey SEC-09");
    }
}
