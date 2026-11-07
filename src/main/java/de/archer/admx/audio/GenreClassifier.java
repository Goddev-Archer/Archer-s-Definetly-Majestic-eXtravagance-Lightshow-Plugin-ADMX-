package de.archer.admx.audio;

import java.util.List;

/**
 * Autonomer Rhythmus- und Genre-Klassifikator mit Autokorrelations- und Periodizitäts-Unterstützung.
 * <p>
 * Erkennt präzise Hard Techno / Schranz (z. B. Klangkuenstler bei 145-165 BPM),
 * klassischen Techno, Rap/Trap, Drum & Bass, House und Ambient - selbst bei Live-Aufnahmen mit
 * Verzerrung, Hall und Offbeat-Hi-Hats.
 */
public final class GenreClassifier {

    public record ClassificationResult(
            MusicGenre genre,
            float confidence,
            double regularityScore // 1.0 = extrem metronomisch (Techno), 0.0 = extrem synkopiert (Rap/Breakbeat)
    ) {}

    /**
     * Primäre Klassifikationsmethode mit Autokorrelations-Periodizität.
     */
    public ClassificationResult classify(double bpm, double periodicityScore, float energy, List<Double> intervals) {
        if (energy < 0.025f) {
            return new ClassificationResult(MusicGenre.AMBIENT, 0.65f, 0.1);
        }

        // Intervall-Regularität berechnen (falls Intervalle vorhanden)
        double intervalRegularity = periodicityScore;
        if (intervals != null && intervals.size() >= 4) {
            List<Double> sorted = intervals.stream().sorted().toList();
            double median = sorted.get(sorted.size() / 2);
            if (median > 1e-4) {
                double varianceSum = 0.0;
                for (double val : intervals) {
                    double ratio = val / median;
                    double diff = ratio - 1.0;
                    varianceSum += diff * diff;
                }
                double normVariance = varianceSum / intervals.size();
                double calcReg = Math.clamp(1.0 - (normVariance * 2.2), 0.0, 1.0);
                intervalRegularity = Math.max(periodicityScore, calcReg);
            }
        }

        double effectiveRegularity = Math.max(periodicityScore, intervalRegularity);

        MusicGenre detectedGenre;
        float confidence;

        // Breiter Techno-Bereich (126 - 168 BPM):
        // Deckt sowohl klassischen Techno (130-140) als auch Hard Techno / Schranz (145-165 BPM wie Klangkuenstler) ab.
        if (bpm >= 126.0 && bpm <= 168.0) {
            if (effectiveRegularity >= 0.35) {
                // Eindeutiger 4-on-the-Floor Beat -> TECHNO / SCHRANZ
                detectedGenre = MusicGenre.TECHNO;
                confidence = (float) Math.clamp(0.85 + effectiveRegularity * 0.14, 0.80, 0.99);
            } else if (bpm >= 158.0) {
                // Schneller Breakbeat bei 160+ BPM ohne 4-on-the-Floor -> Drum & Bass
                detectedGenre = MusicGenre.DRUM_AND_BASS;
                confidence = (float) Math.clamp(0.72 + (1.0 - effectiveRegularity) * 0.24, 0.65, 0.95);
            } else {
                // 130-150 BPM Trap mit synkopierten 808-Kicks
                detectedGenre = MusicGenre.RAP_HIPHOP;
                confidence = (float) Math.clamp(0.70 + (1.0 - effectiveRegularity) * 0.24, 0.60, 0.92);
            }
        } else if (bpm > 168.0) {
            if (effectiveRegularity >= 0.50) {
                detectedGenre = MusicGenre.TECHNO; // Hardcore / Uptempo / Frenchcore
                confidence = 0.88f;
            } else {
                detectedGenre = MusicGenre.DRUM_AND_BASS;
                confidence = 0.90f;
            }
        } else if (bpm >= 115.0 && bpm < 126.0) {
            if (effectiveRegularity >= 0.40) {
                // 120-125 BPM 4/4 Groove -> House / Club
                detectedGenre = MusicGenre.HOUSE_CLUB;
                confidence = (float) Math.clamp(0.80 + effectiveRegularity * 0.16, 0.72, 0.96);
            } else {
                detectedGenre = MusicGenre.RAP_HIPHOP;
                confidence = 0.72f;
            }
        } else {
            // bpm < 115.0: Boom-Bap, Trap, Hip-Hop (65 - 110 BPM)
            if (energy > 0.05f) {
                detectedGenre = MusicGenre.RAP_HIPHOP;
                confidence = (float) Math.clamp(0.80 + (1.0 - effectiveRegularity) * 0.18, 0.72, 0.96);
            } else {
                detectedGenre = MusicGenre.AMBIENT;
                confidence = 0.80f;
            }
        }

        return new ClassificationResult(detectedGenre, confidence, effectiveRegularity);
    }

    /**
     * Abwärtskompatible Überladung.
     */
    public ClassificationResult classify(double bpm, List<Double> intervals, float energy) {
        return classify(bpm, 0.60, energy, intervals);
    }
}
