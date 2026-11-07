package de.archer.admx.audio;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Zentrale Verwaltungs- und Lifecycle-Klasse für Audio-DSP, Beat-Detektion und Genre-Klassifizierung.
 * <p>
 * Garantiert vollkommene Entkopplung vom Paper-Serverthread (PERF-09):
 * Der Main-Thread liest ausschließlich den atomaren {@link BeatSnapshot} aus.
 */
public final class AudioDspManager implements AutoCloseable {

    public enum AudioSourceType {
        BOILER_MEDIA("In-Game Boiler Display / YouTube", "Greift das Audio direkt vom Boiler-Screen auf dem Server ab"),
        LOOPBACK_STREAM("Echtzeit-Stream (127.0.0.1:54321)", "Empfängt PCM-Audio direkt von OBS / Virtual Audio Cable / DJ-Pult"),
        SYNTHETIC_DEMO("Synthetischer Beat-Generator", "Erzeugt realistische Kick-Muster für Techno, Rap und DnB ohne externes Audio");

        private final String name;
        private final String desc;

        AudioSourceType(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        public String sourceName() { return name; }
        public String desc() { return desc; }
    }

    private final BeatDetector beatDetector = new BeatDetector();
    private final GenreClassifier genreClassifier = new GenreClassifier();
    private final AudioSpectrumAnalyzer spectrumAnalyzer = new AudioSpectrumAnalyzer(48000.0);
    private final SoundToLightEngine soundToLightEngine = new SoundToLightEngine(spectrumAnalyzer);
    private final LoopbackAudioReceiver streamReceiver;
    private final BoilerAudioBridge boilerBridge;

    private volatile AudioSourceType activeSource = AudioSourceType.LOOPBACK_STREAM;
    private volatile BeatSnapshot currentSnapshot = BeatSnapshot.idle();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ADMX-Audio-Classifier-Worker");
        t.setDaemon(true);
        return t;
    });

    // Zustand für den synthetischen Beat-Generator
    private double syntheticBpm = 138.0;
    private MusicGenre syntheticGenre = MusicGenre.TECHNO;
    private double syntheticPhase = 0.0;
    private long lastSyntheticTickMs = System.currentTimeMillis();

    public AudioDspManager(int loopbackPort) {
        this.streamReceiver = new LoopbackAudioReceiver(loopbackPort, this::processPcmChunk);
        this.streamReceiver.start();

        this.boilerBridge = new BoilerAudioBridge(beatDetector, this);
        if (this.boilerBridge.tryHook()) {
            this.activeSource = AudioSourceType.BOILER_MEDIA;
        } else {
            this.activeSource = AudioSourceType.LOOPBACK_STREAM;
        }

        // Alle 25 ms (~40 Hz) Genre-Klassifikation und Snapshot aktualisieren
        this.scheduler.scheduleAtFixedRate(this::tickProcessing, 50, 25, TimeUnit.MILLISECONDS);
    }

    public AudioDspManager() {
        this(LoopbackAudioReceiver.DEFAULT_PORT);
    }

    private void tickProcessing() {
        try {
            if (activeSource == AudioSourceType.SYNTHETIC_DEMO) {
                tickSyntheticAudio();
            }

            // Genre klassifizieren
            GenreClassifier.ClassificationResult res = genreClassifier.classify(
                    beatDetector.currentBpm(),
                    beatDetector.getPeriodicityScore(),
                    beatDetector.getCurrentEnergy(),
                    beatDetector.getRecentIntervals()
            );

            // Snapshot erzeugen und atomar bereitstellen
            this.currentSnapshot = beatDetector.createSnapshot(res.genre(), res.confidence());
        } catch (Exception e) {
            // Unhandled exceptions im Worker abfangen, damit Scheduler nicht abbricht
        }
    }

    private void tickSyntheticAudio() {
        long now = System.currentTimeMillis();
        double dtSec = Math.clamp((now - lastSyntheticTickMs) / 1000.0, 0.001, 0.1);
        lastSyntheticTickMs = now;

        double beatsPerSec = syntheticBpm / 60.0;
        double oldPhase = syntheticPhase;
        syntheticPhase += dtSec * beatsPerSec;

        boolean isKick = Math.floor(syntheticPhase) > Math.floor(oldPhase);

        // Bei Rap/Trap Kicks auf Beat 0 und 2.5 (synkopiert) erzeugen
        if (syntheticGenre == MusicGenre.RAP_HIPHOP) {
            double frac = syntheticPhase % 4.0;
            isKick = (oldPhase % 4.0 < 0.05 && frac >= 0.05) || (oldPhase % 4.0 < 2.5 && frac >= 2.5);
        }

        double[] pcm = new double[512];
        if (isKick) {
            // Synthetische Kick-Welle (60 Hz Sinus-Burst) in den BeatDetector speisen
            for (int i = 0; i < 512; i++) {
                double t = i / 44100.0;
                double decay = Math.exp(-t * 28.0);
                pcm[i] = Math.sin(2.0 * Math.PI * 65.0 * t) * decay * 0.95;
            }
        }
        processPcmChunk(pcm);
    }

    /**
     * Zentrale Einspeisestelle für PCM-Audiodaten aller Quellen (Boiler, UDP-Stream, Demo).
     */
    public synchronized void processPcmChunk(double[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        beatDetector.processBlock(pcm);
        spectrumAnalyzer.processPcm(pcm);
    }

    public AudioSpectrumAnalyzer getSpectrumAnalyzer() {
        return spectrumAnalyzer;
    }

    public SoundToLightEngine getSoundToLightEngine() {
        return soundToLightEngine;
    }

    public BeatSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public AudioSourceType getActiveSource() {
        return activeSource;
    }

    public void setActiveSource(AudioSourceType activeSource) {
        this.activeSource = activeSource;
        if (activeSource == AudioSourceType.BOILER_MEDIA && boilerBridge != null) {
            boilerBridge.tryHook();
        }
    }

    public BoilerAudioBridge getBoilerBridge() {
        return boilerBridge;
    }

    public void setSyntheticParameters(double bpm, MusicGenre genre) {
        this.syntheticBpm = Math.clamp(bpm, 50.0, 200.0);
        this.syntheticGenre = genre;
        this.beatDetector.setManualBpm(bpm);
    }

    public BeatDetector getBeatDetector() {
        return beatDetector;
    }

    public GenreClassifier getGenreClassifier() {
        return genreClassifier;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        streamReceiver.close();
        if (boilerBridge != null) {
            boilerBridge.unhook();
        }
    }
}
