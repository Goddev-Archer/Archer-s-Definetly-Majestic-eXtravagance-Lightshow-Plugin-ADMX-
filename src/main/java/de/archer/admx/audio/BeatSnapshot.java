package de.archer.admx.audio;

/**
 * Unveränderlicher, lock-freier Zustand der Audio- und Beat-Erkennung.
 * Wird asynchron durch den AudioDspWorker berechnet und für den Paper-Serverthread
 * im Render-Tick thread-safe bereitgestellt.
 */
public record BeatSnapshot(
        double bpm,
        double phase,             // 0.0 = Downbeat, 0.5 = Offbeat, 0.0..1.0
        float confidence,         // 0.0 .. 1.0 Stabilität des Beat-Trackings
        boolean isKick,           // True, wenn in diesem Tick ein Kick-Onset stattfand
        float energy,             // 0.0 .. 1.0 Momentane Sub-Bass Energie
        MusicGenre genre,
        float genreConfidence,    // 0.0 .. 1.0 Sicherheit der Genre-Klassifizierung
        long barCount,            // Zähler für 4-Viertel-Takte
        int beatInBar,            // 0, 1, 2, 3 innerhalb des 4/4 Taktes
        boolean dropImpact,       // True, wenn ein massiver Drop detektiert wurde
        boolean isKickDriving,    // True, wenn regelmäßige, druckvolle Kicks treiben
        boolean strobeActive,     // True, wenn Laser & Stroboskop aktiv stroben sollen
        float strobeIntensity,    // 0.0 .. 1.0 Stärke des Strobo-Einsatzes
        float strobeHz            // Frequenz in Hz (strikt <= 10.0 Hz per SEC-09)
) {
    /**
     * Kompatibilitäts-Konstruktor für 10 Argumente.
     */
    public BeatSnapshot(
            double bpm,
            double phase,
            float confidence,
            boolean isKick,
            float energy,
            MusicGenre genre,
            float genreConfidence,
            long barCount,
            int beatInBar,
            boolean dropImpact
    ) {
        this(
                bpm,
                phase,
                confidence,
                isKick,
                energy,
                genre,
                genreConfidence,
                barCount,
                beatInBar,
                dropImpact,
                isKick && energy > 0.35f,
                isKick && energy > 0.40f,
                energy,
                Math.min(10.0f, (float) Math.max(6.0, bpm / 15.0))
        );
    }

    public static BeatSnapshot idle() {
        return new BeatSnapshot(
                128.0,
                0.0,
                0.0f,
                false,
                0.0f,
                MusicGenre.AMBIENT,
                0.0f,
                0L,
                0,
                false,
                false,
                false,
                0.0f,
                0.0f
        );
    }

    public static BeatSnapshot synthetic(double bpm, double phase, boolean isKick, float energy, MusicGenre genre) {
        long totalBeats = (long) Math.floor(phase);
        long bar = totalBeats / 4;
        int inBar = (int) (totalBeats % 4);
        double fracPhase = phase - Math.floor(phase);
        boolean driving = isKick || energy > 0.4f;
        float safeHz = (float) Math.min(10.0, Math.max(6.0, bpm / 15.0));

        return new BeatSnapshot(
                bpm,
                fracPhase,
                0.95f,
                isKick,
                energy,
                genre,
                0.95f,
                bar,
                inBar,
                false,
                driving,
                driving,
                driving ? 0.9f : 0.0f,
                safeHz
        );
    }
}
