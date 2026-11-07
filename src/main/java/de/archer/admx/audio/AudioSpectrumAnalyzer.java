package de.archer.admx.audio;

import java.util.Arrays;

/**
 * 24-Band FFT-Spektralanalysator für die Echtzeit-Visualisierung im UI
 * und das Hardware-Style Sound-to-Light Triggersystem.
 * <p>
 * Berechnet 24 logarithmische Frequenzbänder (Schwerpunkt bis 4000 Hz) aus 16-Bit PCM-Audiodaten
 * mit einem 1024-Punkt FFT und Hann-Fenster. Bietet glatte Peak-Decay-Envelopes
 * für ein lebendiges, responsives Balkenspektrogramm im Spiel.
 */
public final class AudioSpectrumAnalyzer {

    public static final int BANDS = 24;
    private static final int FFT_SIZE = 1024;

    private final double sampleRate;
    private final double[] hannWindow = new double[FFT_SIZE];
    private final double[] ringBuffer = new double[FFT_SIZE];
    private int ringIndex = 0;

    // FFT Arbeits-Arrays (ohne Garbage-Collection im Render-Tick)
    private final double[] real = new double[FFT_SIZE];
    private final double[] imag = new double[FFT_SIZE];

    // Logarithmische Band-Grenzfrequenzen in Hz
    private final int[] bandStartBins = new int[BANDS];
    private final int[] bandEndBins = new int[BANDS];
    private final int[] bandCenterHz = new int[BANDS];

    // Geglättete Band-Pegel [0.0 .. 1.0]
    private final float[] bandLevels = new float[BANDS];
    private final float[] peakHold = new float[BANDS];

    public AudioSpectrumAnalyzer(double sampleRate) {
        this.sampleRate = sampleRate;

        // Hann-Fenster vorberechnen
        for (int i = 0; i < FFT_SIZE; i++) {
            hannWindow[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }

        // 24 logarithmische Frequenzbänder von 30 Hz bis 16.000 Hz konfigurieren
        // (Bänder 0..14 decken exakt 30 Hz bis 4000 Hz ab)
        double minFreq = 30.0;
        double maxFreq = 16000.0;
        double hzPerBin = sampleRate / FFT_SIZE;

        for (int band = 0; band < BANDS; band++) {
            double fLow = minFreq * Math.pow(maxFreq / minFreq, (double) band / BANDS);
            double fHigh = minFreq * Math.pow(maxFreq / minFreq, (double) (band + 1) / BANDS);

            int bStart = Math.max(1, (int) Math.floor(fLow / hzPerBin));
            int bEnd = Math.min(FFT_SIZE / 2, Math.max(bStart + 1, (int) Math.ceil(fHigh / hzPerBin)));

            bandStartBins[band] = bStart;
            bandEndBins[band] = bEnd;
            bandCenterHz[band] = (int) Math.round(Math.sqrt(fLow * fHigh));
        }
    }

    public AudioSpectrumAnalyzer() {
        this(48000.0);
    }

    /**
     * Speist PCM-Audio-Samples [-1.0 .. 1.0] ein und aktualisiert die Spektrogramm-Bänder.
     */
    public synchronized void processPcm(double[] pcm) {
        for (double sample : pcm) {
            ringBuffer[ringIndex] = sample;
            ringIndex = (ringIndex + 1) % FFT_SIZE;
        }

        // Nur berechnen, wenn genügend Samples eingetroffen sind
        if (pcm.length >= 256) {
            computeFft();
        }
    }

    private void computeFft() {
        // Ringpuffer linearisieren und mit Hann-Fenster multiplizieren
        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = (ringIndex + i) % FFT_SIZE;
            real[i] = ringBuffer[idx] * hannWindow[i];
            imag[i] = 0.0;
        }

        // In-Place Cooley-Tukey Radix-2 FFT
        int n = FFT_SIZE;
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (j > i) {
                double tr = real[j]; real[j] = real[i]; real[i] = tr;
                double ti = imag[j]; imag[j] = imag[i]; imag[i] = ti;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wStepR = Math.cos(angle);
            double wStepI = Math.sin(angle);
            int half = len >> 1;

            for (int i = 0; i < n; i += len) {
                double wR = 1.0;
                double wI = 0.0;
                for (int k = 0; k < half; k++) {
                    int pos = i + k;
                    int match = pos + half;
                    double uR = real[pos];
                    double uI = imag[pos];
                    double vR = real[match] * wR - imag[match] * wI;
                    double vI = real[match] * wI + imag[match] * wR;

                    real[pos] = uR + vR;
                    imag[pos] = uI + vI;
                    real[match] = uR - vR;
                    imag[match] = uI - vI;

                    double nextWR = wR * wStepR - wI * wStepI;
                    wI = wR * wStepI + wI * wStepR;
                    wR = nextWR;
                }
            }
        }

        // Magnituden in logarithmische Bänder aggregieren mit dB-Skalierung
        for (int band = 0; band < BANDS; band++) {
            int start = bandStartBins[band];
            int end = bandEndBins[band];
            double maxMag = 0.0;

            for (int bin = start; bin < end; bin++) {
                double mag = Math.hypot(real[bin], imag[bin]) * (4.0 / FFT_SIZE);
                if (mag > maxMag) {
                    maxMag = mag;
                }
            }

            // Dynamikbereich ca. 55 dB auf [0.0 .. 1.0] mappen
            double db = 20.0 * Math.log10(Math.max(1e-5, maxMag));
            float rawLevel = (float) Math.clamp((db + 55.0) / 55.0, 0.0, 1.0);

            // Bounciger Peak-Follower (schneller Attack, weicher Decay)
            if (rawLevel >= bandLevels[band]) {
                bandLevels[band] = rawLevel;
            } else {
                bandLevels[band] = Math.max(0.0f, bandLevels[band] * 0.86f);
            }

            // Peak Hold Marker
            if (bandLevels[band] >= peakHold[band]) {
                peakHold[band] = bandLevels[band];
            } else {
                peakHold[band] = Math.max(0.0f, peakHold[band] * 0.95f);
            }
        }
    }

    /**
     * Gibt eine Kopie der aktuellen 24 Band-Pegel [0.0 .. 1.0] zurück.
     */
    public synchronized float[] currentBands() {
        return Arrays.copyOf(bandLevels, BANDS);
    }

    /**
     * Berechnet den gemittelten Energiepegel [0.0 .. 1.0] für ein frei wählbares
     * Frequenzfenster [lowHz .. highHz] (gedeckelt bei max. 4000 Hz).
     */
    public synchronized float getLevelForRange(int lowHz, int highHz) {
        int l = Math.max(30, lowHz);
        int h = Math.min(4000, Math.max(l + 20, highHz));

        float sum = 0.0f;
        int count = 0;

        for (int band = 0; band < BANDS; band++) {
            int center = bandCenterHz[band];
            if (center >= l && center <= h) {
                sum += bandLevels[band];
                count++;
            }
        }

        if (count == 0) {
            // Nächstgelegenes Band wählen, falls Bereich sehr schmal
            for (int band = 0; band < BANDS; band++) {
                if (bandCenterHz[band] >= l) {
                    return bandLevels[band];
                }
            }
            return bandLevels[0];
        }

        return Math.clamp(sum / count, 0.0f, 1.0f);
    }

    public synchronized void reset() {
        Arrays.fill(ringBuffer, 0.0);
        Arrays.fill(bandLevels, 0.0f);
        Arrays.fill(peakHold, 0.0f);
        ringIndex = 0;
    }
}
