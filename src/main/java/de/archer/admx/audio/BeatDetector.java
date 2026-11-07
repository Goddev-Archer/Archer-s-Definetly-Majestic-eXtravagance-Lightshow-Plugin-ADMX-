package de.archer.admx.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Hochpräziser, industrie-inspirierter Beat- und Tempo-Tracker für Live-Audio (BTrack / Davies & Plumbley / Aubio).
 * <p>
 * Entwickelt für anspruchsvolle Live-Sets und Festival-Aufnahmen (z. B. Klangkuenstler Schranz / Hard Techno bei 145-165 BPM):
 * <ul>
 *   <li><b>Automatic Gain Control (AGC):</b> Unempfindlich gegen niedrige oder übersteuerte Video-/Stream-Lautstärken.</li>
 *   <li><b>3-Band Spektral-Novelty (ODF):</b> Sub-Bass (35-130 Hz), Punch (130-2200 Hz) und Treble-Transienten (2.2-9.5 kHz).
 *       Eliminiert durchgehenden Schranz-Bass-Rumble und isoliert den harten Kick-Transient.</li>
 *   <li><b>Kausale Autokorrelation & Shift-Invariante Kammfilterbank:</b> Ermittelt das Tempo über ein 3-Sekunden-Fenster.
 *       Verhindert Sprünge oder Drift durch Offbeat-Hi-Hats, Crowd-Geschrei oder Snares.</li>
 *   <li><b>Gated Phase-Locked Loop (PLL):</b> Offbeat-Hi-Hats werden phasenmäßig ausgefiltert (Gated Out),
 *       sodass der Downbeat felsenfest auf der Kick verankert bleibt.</li>
 *   <li><b>Autonome Stroboskop-Logik:</b> Erkennt treibende Kicks, schaltet bei Drops/Kicks das Stroboskop zu
 *       und bei Ruhephasen/Breakdowns sofort ab (strikt gedeckelt auf <= 10.0 Hz gem. SEC-09).</li>
 * </ul>
 */
public final class BeatDetector {

    private static final int ODF_HISTORY_LEN = 256; // ~2.97 Sekunden bei 512 Samples / 44.1 kHz (86.13 Hz)
    private static final double MIN_BPM = 60.0;
    private static final double MAX_BPM = 195.0;

    private final SubBassBandpassFilter subBassFilter;
    private final SubBassBandpassFilter kickPunchFilter;
    private final SubBassBandpassFilter highTransientFilter;

    private final double sampleRate;
    private final int hopSize;

    // Automatic Gain Control (AGC) & Vorherige Band-Energien
    private double peakSub = 0.05;
    private double peakPunch = 0.05;
    private double peakHigh = 0.05;
    private double prevNormSub = 0.0;
    private double prevNormPunch = 0.0;
    private double prevNormHigh = 0.0;

    // Zirkulärer Puffer für Onset Detection Function (ODF)
    private final double[] odfHistory = new double[ODF_HISTORY_LEN];
    private int odfIndex = 0;
    private int odfCount = 0;

    // Autokorrelation & Kammfilterbank (Davies & Plumbley / BTrack)
    private double currentBpm = 140.0;
    private double periodicityScore = 0.0; // 0.0 .. 1.0 (Maß für 4-on-the-Floor Regelmäßigkeit)
    private float confidence = 0.0f;
    private int acfSkipCounter = 0;

    // Phasen-Tracking (Gated Phase-Locked Loop)
    private double continuousBeatPosition = 0.0;
    private double currentTimeSec = 0.0;
    private double lastDownbeatSec = -1.0;
    private boolean kickTriggeredThisStep = false;
    private boolean dropImpact = false;
    private float currentEnergy = 0.0f;
    private float energyEnvelope = 0.0f;

    // Gespeicherte Downbeat-Intervalle für Abwärtskompatibilität
    private final Deque<Double> recentIntervals = new ArrayDeque<>(24);

    // Autonome Strobe- & Kick-Driving-Erkennung
    private int drivingKickStreak = 0;
    private boolean isKickDriving = false;
    private boolean strobeActive = false;
    private float strobeIntensity = 0.0f;
    private float strobeHz = 0.0f;

    // Breakdown- & Drop-Erkennung
    private int lowEnergyStreakBlocks = 0;

    public BeatDetector(double sampleRate, int hopSize) {
        this.sampleRate = sampleRate;
        this.hopSize = hopSize;
        // 3 Frequenzbänder:
        // 1. Sub-Bass Fundamental (35-130 Hz): Tiefe Kicks & 808
        this.subBassFilter = new SubBassBandpassFilter(sampleRate, 35.0, 130.0);
        // 2. Punch & Beater Attack (130-2200 Hz): Kick-Klick & Snare
        this.kickPunchFilter = new SubBassBandpassFilter(sampleRate, 130.0, 2200.0);
        // 3. High Transients & Hats (2200-9500 Hz): Hi-Hats & Claps
        this.highTransientFilter = new SubBassBandpassFilter(sampleRate, 2200.0, 9500.0);
    }

    public BeatDetector() {
        this(44100.0, 512);
    }

    /**
     * Verarbeitet einen Audio-Block (typischerweise 512 Samples = 11.6 ms bei 44.1 kHz).
     */
    public synchronized void processBlock(double[] pcm) {
        int length = pcm.length;
        if (length == 0) return;

        double blockTimeSec = length / sampleRate;
        currentTimeSec += blockTimeSec;
        kickTriggeredThisStep = false;
        dropImpact = false;

        // 1. 3-Band Energie berechnen
        double subSum = 0.0;
        double punchSum = 0.0;
        double highSum = 0.0;
        for (double sample : pcm) {
            double s = subBassFilter.processSample(sample);
            subSum += s * s;
            double p = kickPunchFilter.processSample(sample);
            punchSum += p * p;
            double h = highTransientFilter.processSample(sample);
            highSum += h * h;
        }

        double subRms = Math.sqrt(subSum / length);
        double punchRms = Math.sqrt(punchSum / length);
        double highRms = Math.sqrt(highSum / length);

        // 2. Automatic Gain Control (AGC): Unabhängig von YouTube/Stream-Lautstärke
        peakSub = Math.max(subRms, peakSub * 0.9982 + 0.00005);
        peakPunch = Math.max(punchRms, peakPunch * 0.9982 + 0.00005);
        peakHigh = Math.max(highRms, peakHigh * 0.9982 + 0.00005);

        double normSub = Math.clamp(subRms / Math.max(0.004, peakSub), 0.0, 1.0);
        double normPunch = Math.clamp(punchRms / Math.max(0.004, peakPunch), 0.0, 1.0);
        double normHigh = Math.clamp(highRms / Math.max(0.004, peakHigh), 0.0, 1.0);

        // Subjektive Audio-Energie mit exponentiellem Leaky-Decay
        double instantEnergy = Math.max(normSub * 0.85, normPunch * 0.65);
        this.energyEnvelope = (float) Math.max(instantEnergy, this.energyEnvelope * 0.94);
        this.currentEnergy = (float) Math.clamp(this.energyEnvelope, 0.0, 1.0);

        // 3. Multi-Band Spektraler Fluss (Half-Wave Rectified Novelty ODF)
        // Dämpft stehende Sinus-Bässe (Schranz Rumble) auf ~0 ab und verstärkt Transienten
        double diffSub = Math.max(0.0, normSub - 0.72 * prevNormSub);
        double diffPunch = Math.max(0.0, normPunch - 0.70 * prevNormPunch);
        double diffHigh = Math.max(0.0, normHigh - 0.70 * prevNormHigh);

        prevNormSub = normSub;
        prevNormPunch = normPunch;
        prevNormHigh = normHigh;

        double odfVal = diffSub * 0.55 + diffPunch * 0.35 + diffHigh * 0.10;

        // ODF in den Ringspeicher schreiben
        odfHistory[odfIndex] = odfVal;
        odfIndex = (odfIndex + 1) % ODF_HISTORY_LEN;
        if (odfCount < ODF_HISTORY_LEN) {
            odfCount++;
        }

        // 4. Periodizität & Tempo-Induktion via Kammfilter-Autokorrelation (alle 2 Blöcke = ~23 ms)
        acfSkipCounter++;
        if (acfSkipCounter >= 2) {
            acfSkipCounter = 0;
            updateTempoAutocorrelation();
        }

        // 5. Phase-Locked Loop (PLL) Taktschritt weiterrücken
        double beatsPerSec = currentBpm / 60.0;
        continuousBeatPosition += blockTimeSec * beatsPerSec;
        double beatPeriodSec = 1.0 / beatsPerSec;

        // Phase innerhalb des Taktschlags [0.0 .. 1.0)
        double currentPhase = continuousBeatPosition - Math.floor(continuousBeatPosition);

        // 6. Dynamischer Onset-Schwellenwert
        double meanOdf = 0.0;
        for (int i = 0; i < odfCount; i++) {
            meanOdf += odfHistory[i];
        }
        meanOdf = (odfCount > 0) ? (meanOdf / odfCount) : 0.0;

        double varOdf = 0.0;
        for (int i = 0; i < odfCount; i++) {
            double d = odfHistory[i] - meanOdf;
            varOdf += d * d;
        }
        double stdOdf = Math.sqrt((odfCount > 0) ? (varOdf / odfCount) : 0.0);
        double onsetThreshold = meanOdf + 1.15 * stdOdf + 0.003;

        boolean isOnset = odfVal > onsetThreshold;

        // 7. Gated Beat Prediction (Schützt vor Offbeat-Hi-Hats und Crowd-Noise)
        if (isOnset) {
            // Fällt der Onset nahe an den erwarteten Downbeat (+/- 24% Toleranz)?
            boolean nearDownbeat = (currentPhase < 0.24 || currentPhase > 0.76);
            boolean nearOffbeat = (currentPhase >= 0.38 && currentPhase <= 0.62);

            if (nearDownbeat) {
                // Echter Downbeat Kick!
                kickTriggeredThisStep = true;

                // Phasennachführung (sanfter Phase Lock auf den Downbeat)
                double phaseError = (currentPhase > 0.5) ? (currentPhase - 1.0) : currentPhase;
                continuousBeatPosition -= phaseError * 0.30;

                if (lastDownbeatSec >= 0) {
                    double interval = currentTimeSec - lastDownbeatSec;
                    if (interval >= 0.25 && interval <= 1.45) {
                        if (recentIntervals.size() >= 16) {
                            recentIntervals.removeFirst();
                        }
                        recentIntervals.addLast(interval);
                    }
                }

                // Drop-Erkennung: Vorher mind. 1.2s ruhig gewesen, und jetzt schlägt die Kick voll ein?
                if (lowEnergyStreakBlocks > 28 && currentEnergy > 0.38f) {
                    dropImpact = true;
                }
                lowEnergyStreakBlocks = 0;
                lastDownbeatSec = currentTimeSec;
                drivingKickStreak = Math.min(32, drivingKickStreak + 2);
            } else if (!nearOffbeat) {
                // Rhythmische Synkope (z. B. Trap/Breakbeat) - kein Downbeat-Phasensprung
                lowEnergyStreakBlocks = 0;
            }
        } else {
            if (normSub < 0.08 && normPunch < 0.08) {
                lowEnergyStreakBlocks++;
            } else {
                lowEnergyStreakBlocks = Math.max(0, lowEnergyStreakBlocks - 1);
            }
        }

        // 8. Autonome Stroboskop- und Kick-Driving-Entscheidung
        boolean withinBeatWindow = lastDownbeatSec >= 0 && (currentTimeSec - lastDownbeatSec) <= (beatPeriodSec * 1.75);
        if (!withinBeatWindow) {
            drivingKickStreak = Math.max(0, drivingKickStreak - 1);
        }

        // Kicks treiben aktiv, wenn Streak vorhanden, wir im Taktfenster sind und Periodizität hoch ist
        boolean kicksDriving = (drivingKickStreak >= 3 && withinBeatWindow && (periodicityScore >= 0.28 || currentEnergy > 0.12f));
        this.isKickDriving = kicksDriving;

        // Stroboskop ist aktiv bei treibenden Kicks ODER auf einem Drop-Impact
        this.strobeActive = kicksDriving || dropImpact;
        this.strobeIntensity = strobeActive ? (float) Math.clamp(currentEnergy * 1.35, 0.45, 1.0) : 0.0f;

        // Frequenz dynamisch an BPM koppeln, strikt gedeckelt auf <= 10.0 Hz gem. SEC-09
        float computedHz = (float) Math.min(10.0, Math.max(6.0, currentBpm / 15.0));
        this.strobeHz = strobeActive ? computedHz : 0.0f;
    }

    /**
     * Führt eine Kammfilter-Autokorrelation (Davies & Plumbley / BTrack) über die ODF-Historie durch.
     */
    private void updateTempoAutocorrelation() {
        if (odfCount < 48) {
            return;
        }

        // 1. Zirkulären ODF-Puffer linearisieren
        double[] odf = new double[odfCount];
        int start = (odfIndex - odfCount + ODF_HISTORY_LEN) % ODF_HISTORY_LEN;
        double sumOdf = 0.0;
        for (int i = 0; i < odfCount; i++) {
            double val = odfHistory[(start + i) % ODF_HISTORY_LEN];
            odf[i] = val;
            sumOdf += val;
        }

        double meanOdf = sumOdf / odfCount;
        for (int i = 0; i < odfCount; i++) {
            odf[i] = Math.max(0.0, odf[i] - meanOdf);
        }

        // 2. Relevante Lags für 60 BPM bis 195 BPM berechnen
        // tau = (60 * Fs) / (hop * BPM) = (60 * 44100) / (512 * BPM) = 5167.97 / BPM
        int minLag = Math.max(20, (int) Math.floor((sampleRate * 60.0) / (hopSize * MAX_BPM))); // ~26
        int maxLag = Math.min((int) Math.ceil((sampleRate * 60.0) / (hopSize * MIN_BPM)), odfCount / 2); // ~86

        if (maxLag <= minLag) {
            return;
        }

        double[] acf = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0.0;
            int count = odfCount - lag;
            for (int i = 0; i < count; i++) {
                sum += odf[i] * odf[i + lag];
            }
            acf[lag] = sum / count;
        }

        // 3. Shift-invariante Kammfilterbank (Davies & Plumbley)
        double bestScore = 0.0;
        int bestLag = -1;
        double sumScores = 0.0;
        int scoredLags = 0;

        for (int lag = minLag; lag <= maxLag; lag++) {
            double r1 = acf[lag];
            double r2 = (lag * 2 <= maxLag) ? acf[lag * 2] : 0.0;
            double rHalf = (lag / 2 >= minLag) ? acf[lag / 2] : 0.0;

            // Harmonische Kammresonanz (Viertel + Halb + Achtel)
            double comb = r1 + 0.58 * r2 + 0.22 * rHalf;

            // Tempo-Präferenzkurve (Rayleigh / Gaussian um 138 BPM, tau ~ 37.5)
            // Schützt vor Oktavfehlern (z. B. 75 vs 150 BPM)
            double tauPref = (sampleRate * 60.0) / (hopSize * 138.0);
            double sigma = 22.0;
            double weight = Math.exp(-0.5 * Math.pow((lag - tauPref) / sigma, 2));

            double score = comb * (0.42 + 0.58 * weight);
            sumScores += score;
            scoredLags++;

            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }

        if (bestLag > 0 && scoredLags > 0) {
            double avgScore = sumScores / scoredLags;
            double peakRatio = (avgScore > 1e-8) ? (bestScore / avgScore) : 1.0;
            this.periodicityScore = Math.clamp((peakRatio - 1.0) / 2.2, 0.0, 1.0);

            // Sub-Sample parabolische Interpolation für 0.1 BPM Auflösung
            double exactLag = bestLag;
            if (bestLag > minLag && bestLag < maxLag) {
                double y0 = acf[bestLag - 1];
                double y1 = acf[bestLag];
                double y2 = acf[bestLag + 1];
                double denom = 2.0 * (2.0 * y1 - y0 - y2);
                if (Math.abs(denom) > 1e-7) {
                    exactLag += Math.clamp((y2 - y0) / denom, -0.5, 0.5);
                }
            }

            double estimatedBpm = (sampleRate * 60.0) / (hopSize * exactLag);
            estimatedBpm = Math.clamp(estimatedBpm, MIN_BPM, MAX_BPM);

            // Glättung
            double alpha = (this.confidence < 0.40f) ? 0.38 : 0.16;
            this.currentBpm = alpha * estimatedBpm + (1.0 - alpha) * this.currentBpm;
            this.confidence = (float) Math.clamp(this.periodicityScore * 1.15, 0.15, 0.99);
        }
    }

    /**
     * Erstellt einen synchronen Snapshot für den Render-Thread.
     */
    public synchronized BeatSnapshot createSnapshot(MusicGenre genre, float genreConfidence) {
        long totalBeats = (long) Math.floor(continuousBeatPosition);
        long barCount = Math.floorDiv(totalBeats, 4);
        int beatInBar = (int) Math.floorMod(totalBeats, 4);
        double phase = continuousBeatPosition - Math.floor(continuousBeatPosition);

        return new BeatSnapshot(
                currentBpm,
                phase,
                confidence,
                kickTriggeredThisStep,
                currentEnergy,
                genre,
                genreConfidence,
                barCount,
                beatInBar,
                dropImpact,
                isKickDriving,
                strobeActive,
                strobeIntensity,
                strobeHz
        );
    }

    public synchronized List<Double> getRecentIntervals() {
        return new ArrayList<>(recentIntervals);
    }

    public synchronized double currentBpm() {
        return currentBpm;
    }

    public synchronized double getPeriodicityScore() {
        return periodicityScore;
    }

    public synchronized float getCurrentEnergy() {
        return currentEnergy;
    }

    public synchronized boolean isKickDriving() {
        return isKickDriving;
    }

    public synchronized boolean isStrobeActive() {
        return strobeActive;
    }

    public synchronized float strobeHz() {
        return strobeHz;
    }

    public synchronized void setManualBpm(double bpm) {
        this.currentBpm = Math.clamp(bpm, 40.0, 240.0);
        this.confidence = 1.0f;
    }

    public synchronized void reset() {
        subBassFilter.reset();
        kickPunchFilter.reset();
        highTransientFilter.reset();
        peakSub = 0.05;
        peakPunch = 0.05;
        peakHigh = 0.05;
        prevNormSub = 0.0;
        prevNormPunch = 0.0;
        prevNormHigh = 0.0;
        odfIndex = 0;
        odfCount = 0;
        for (int i = 0; i < ODF_HISTORY_LEN; i++) {
            odfHistory[i] = 0.0;
        }
        recentIntervals.clear();
        lastDownbeatSec = -1.0;
        continuousBeatPosition = 0.0;
        confidence = 0.0f;
        energyEnvelope = 0.0f;
        drivingKickStreak = 0;
        isKickDriving = false;
        strobeActive = false;
        strobeIntensity = 0.0f;
        strobeHz = 0.0f;
        lowEnergyStreakBlocks = 0;
        periodicityScore = 0.0;
    }
}
