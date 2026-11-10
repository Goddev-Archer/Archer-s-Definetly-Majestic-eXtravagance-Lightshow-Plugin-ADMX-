package de.archer.admx.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import de.archer.admx.control.MovementPattern;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Gespeichertes "Phase-Locked Song-DNA" Auto-Show-Profil ({@code auto_show_profiles}).
 * <p>
 * Anstatt starre Stoppuhr-Zeitstempel aufzuzeichnen (die bei leicht verspätetem Klick, Stream-Buffering
 * oder BPM-Pitching sofort asynchron zur Musik laufen würden), speichert dieses Profil die über
 * mehrere Song-Durchläufe (Multi-Pass) gelernte <b>musikalische Struktur-DNA</b> eines Liedes:
 * <ul>
 *     <li>Kalibrierte Dynamik-Fenster ({@code learnedFloorEnergy} bis {@code learnedPeakEnergy})</li>
 *     <li>Normalisierte Schwellenwerte für {@link SongSection#AMBIENT_BREAKDOWN}, {@link SongSection#VERSE_GROOVE},
 *         {@link SongSection#RISING_BUILDUP} und {@link SongSection#PEAK_DROP}</li>
 *     <li>Referenz-BPM, Phrasen-Rasterung (4/8/16 Takte) und Bass-Dominanz</li>
 *     <li>Fest zugewiesene Licht-, Farb- und Bewegungs-Choreografie pro Song-Sektion</li>
 * </ul>
 * Beim Abspielen wird die Choreografie in Echtzeit über die <b>Live-Beat-PLL ({@link BeatSnapshot#phase()})</b>
 * getaktet ("3-Stufen-Idiotenschutz"), sodass die Show unabhängig vom Startzeitpunkt immer 100 % synchron auf dem Beat sitzt.
 */
public final class AutoShowProfile {

    public static final int MAX_QUICK_SLOTS = 8;
    public static final float MAX_SAFE_STROBE_HZ = 10.0f; // SEC-09 / SEC-15

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Die 4 musikalischen Energie-Sektionen eines Songs, die in Echtzeit erkannt werden.
     */
    public enum SongSection {
        AMBIENT_BREAKDOWN("Breakdown / Intro", "Ruhige Fläche, weiche Sweeps, 0 Hz Strobe"),
        VERSE_GROOVE("Verse / Groove", "Rhythmischer Beat-Puls im Takt-Raster"),
        RISING_BUILDUP("Buildup / Snare-Roll", "Ansteigende Energie, Fächer öffnet sich zum Drop"),
        PEAK_DROP("Peak Drop / Mainpart", "Maximale Energie, schnelle Schnitte, Kick-Blinder & Strobe");

        private final String displayName;
        private final String description;

        SongSection(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }
    }

    /**
     * Kuratierte Stil-Archetypen, durch die der Nutzer während oder nach dem Lernen schalten kann.
     */
    public enum ChoreoStyle {
        CYBERPUNK_RAVE("Cyberpunk Rave", 190f, 315f, 0.92f, MovementPattern.FIGURE_8, MovementPattern.CIRCLE, 9.0f),
        FESTIVAL_MAINSTAGE("Festival Mainstage", 15f, 52f, 0.88f, MovementPattern.PAN_SWEEP, MovementPattern.FAN_SPREAD, 10.0f),
        DEEP_WAREHOUSE("Deep Warehouse", 230f, 0f, 0.95f, MovementPattern.TILT_WAVE, MovementPattern.PAN_SWEEP, 8.0f),
        ARCTIC_LASER("Arctic Laser Club", 175f, 210f, 0.80f, MovementPattern.FAN_SPREAD, MovementPattern.CIRCLE, 7.5f),
        SUNSET_GROOVE("Warm Sunset Groove", 28f, 340f, 0.85f, MovementPattern.TILT_WAVE, MovementPattern.FIGURE_8, 5.0f),
        INFERNO_ARENA("Inferno Pyro Arena", 8f, 42f, 0.96f, MovementPattern.CIRCLE, MovementPattern.FAN_SPREAD, 10.0f),
        SCHRANZ_STACCATO("146 BPM Hard-Snap", 38f, 195f, 0.98f, MovementPattern.PAN_SWEEP, MovementPattern.HARD_SNAP_STACCATO, 10.0f);

        private final String displayName;
        private final float primaryHue;
        private final float secondaryHue;
        private final float saturation;
        private final MovementPattern versePattern;
        private final MovementPattern dropPattern;
        private final float dropStrobeHz;

        ChoreoStyle(
                String displayName,
                float primaryHue,
                float secondaryHue,
                float saturation,
                MovementPattern versePattern,
                MovementPattern dropPattern,
                float dropStrobeHz
        ) {
            this.displayName = displayName;
            this.primaryHue = primaryHue;
            this.secondaryHue = secondaryHue;
            this.saturation = saturation;
            this.versePattern = versePattern;
            this.dropPattern = dropPattern;
            this.dropStrobeHz = Math.clamp(dropStrobeHz, 0.0f, MAX_SAFE_STROBE_HZ);
        }

        public String displayName() {
            return displayName;
        }

        public float primaryHue() {
            return primaryHue;
        }

        public float secondaryHue() {
            return secondaryHue;
        }

        public float saturation() {
            return saturation;
        }

        public MovementPattern versePattern() {
            return versePattern;
        }

        public MovementPattern dropPattern() {
            return dropPattern;
        }

        public float dropStrobeHz() {
            return dropStrobeHz;
        }

        public ChoreoStyle next() {
            ChoreoStyle[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    private String profileId;
    private String displayName;
    private int slotIndex; // 0..7 (Slot #1..#8)
    private MusicGenre referenceGenre;
    private ChoreoStyle choreoStyle;
    private double referenceBpm;
    private int phraseLengthBars;

    // Gelernte Song-DNA (Dynamik-Kalibrierung & Sektions-Schwellen)
    private float learnedFloorEnergy;
    private float learnedPeakEnergy;
    private float breakdownThreshold;
    private float buildupSlopeThreshold;
    private float dropThreshold;
    private float bassDominanceRatio;

    // Gelernte Choreografie-Parameter
    private float primaryHueDeg;
    private float secondaryHueDeg;
    private float saturation;
    private MovementPattern breakdownPattern;
    private MovementPattern versePattern;
    private MovementPattern buildupPattern;
    private MovementPattern dropPattern;
    private float speedMultiplier;
    private float amplitude;
    private float phaseSpread;
    private float maxStrobeHz;
    private boolean blinderFlashOnKick;
    private boolean pyroOnDropBar;

    // Multi-Pass Trainings-Metriken
    private int learnedBeats;
    private int completedPasses;
    private int detectedDrops;
    private float confidence;

    public AutoShowProfile(
            String profileId,
            String displayName,
            int slotIndex,
            MusicGenre referenceGenre,
            ChoreoStyle choreoStyle,
            double referenceBpm,
            int phraseLengthBars,
            float learnedFloorEnergy,
            float learnedPeakEnergy,
            float breakdownThreshold,
            float buildupSlopeThreshold,
            float dropThreshold,
            float bassDominanceRatio,
            float primaryHueDeg,
            float secondaryHueDeg,
            float saturation,
            MovementPattern breakdownPattern,
            MovementPattern versePattern,
            MovementPattern buildupPattern,
            MovementPattern dropPattern,
            float speedMultiplier,
            float amplitude,
            float phaseSpread,
            float maxStrobeHz,
            boolean blinderFlashOnKick,
            boolean pyroOnDropBar,
            int learnedBeats,
            int completedPasses,
            int detectedDrops,
            float confidence
    ) {
        this.profileId = sanitizeId(profileId);
        this.displayName = (displayName == null || displayName.isBlank()) ? "Auto-Show #" + (slotIndex + 1) : displayName.trim();
        this.slotIndex = Math.clamp(slotIndex, 0, MAX_QUICK_SLOTS - 1);
        this.referenceGenre = referenceGenre != null ? referenceGenre : MusicGenre.TECHNO;
        this.choreoStyle = choreoStyle != null ? choreoStyle : ChoreoStyle.CYBERPUNK_RAVE;
        this.referenceBpm = Math.clamp(referenceBpm, 60.0, 200.0);
        this.phraseLengthBars = Math.clamp(phraseLengthBars, 2, 32);
        this.learnedFloorEnergy = Math.clamp(learnedFloorEnergy, 0.0f, 0.50f);
        this.learnedPeakEnergy = Math.clamp(learnedPeakEnergy, 0.20f, 1.0f);
        this.breakdownThreshold = Math.clamp(breakdownThreshold, 0.10f, 0.55f);
        this.buildupSlopeThreshold = Math.clamp(buildupSlopeThreshold, 0.02f, 0.35f);
        this.dropThreshold = Math.clamp(dropThreshold, 0.45f, 0.95f);
        this.bassDominanceRatio = Math.clamp(bassDominanceRatio, 0.20f, 2.50f);
        this.primaryHueDeg = ((primaryHueDeg % 360f) + 360f) % 360f;
        this.secondaryHueDeg = ((secondaryHueDeg % 360f) + 360f) % 360f;
        this.saturation = Math.clamp(saturation, 0.10f, 1.0f);
        this.breakdownPattern = breakdownPattern != null ? breakdownPattern : MovementPattern.TILT_WAVE;
        this.versePattern = versePattern != null ? versePattern : MovementPattern.PAN_SWEEP;
        this.buildupPattern = buildupPattern != null ? buildupPattern : MovementPattern.FAN_SPREAD;
        this.dropPattern = dropPattern != null ? dropPattern : MovementPattern.CIRCLE;
        this.speedMultiplier = Math.clamp(speedMultiplier, 0.25f, 3.0f);
        this.amplitude = Math.clamp(amplitude, 0.15f, 1.0f);
        this.phaseSpread = Math.clamp(phaseSpread, 0.0f, 1.0f);
        this.maxStrobeHz = Math.clamp(maxStrobeHz, 0.0f, MAX_SAFE_STROBE_HZ);
        this.blinderFlashOnKick = blinderFlashOnKick;
        this.pyroOnDropBar = pyroOnDropBar;
        this.learnedBeats = Math.max(0, learnedBeats);
        this.completedPasses = Math.max(0, completedPasses);
        this.detectedDrops = Math.max(0, detectedDrops);
        this.confidence = Math.clamp(confidence, 0.0f, 1.0f);
    }

    public static AutoShowProfile createFreshLearningProfile(int slotIndex, String name, ChoreoStyle style) {
        ChoreoStyle s = style != null ? style : ChoreoStyle.CYBERPUNK_RAVE;
        String id = String.format(Locale.ROOT, "show_%02d", Math.clamp(slotIndex + 1, 1, MAX_QUICK_SLOTS));
        return new AutoShowProfile(
                id,
                name != null && !name.isBlank() ? name : s.displayName(),
                slotIndex,
                MusicGenre.TECHNO,
                s,
                128.0,
                8,
                0.06f,
                0.78f,
                0.26f,
                0.07f,
                0.68f,
                1.15f,
                s.primaryHue(),
                s.secondaryHue(),
                s.saturation(),
                MovementPattern.TILT_WAVE,
                s.versePattern(),
                MovementPattern.FAN_SPREAD,
                s.dropPattern(),
                1.0f,
                0.85f,
                0.65f,
                s.dropStrobeHz(),
                true,
                true,
                0,
                0,
                0,
                0.0f
        );
    }

    public static List<AutoShowProfile> createDefaultSeedProfiles() {
        List<AutoShowProfile> defaults = new ArrayList<>();
        defaults.add(new AutoShowProfile(
                "show_01", "Cyberpunk Rave DNA", 0, MusicGenre.TECHNO, ChoreoStyle.CYBERPUNK_RAVE,
                130.0, 8, 0.05f, 0.85f, 0.25f, 0.07f, 0.68f, 1.25f,
                190f, 315f, 0.92f,
                MovementPattern.TILT_WAVE, MovementPattern.FIGURE_8, MovementPattern.FAN_SPREAD, MovementPattern.CIRCLE,
                1.25f, 0.90f, 0.75f, 9.0f, true, true,
                0, 0, 0, 0.0f
        ));
        defaults.add(new AutoShowProfile(
                "show_02", "Festival Mainstage Drop", 1, MusicGenre.HOUSE_CLUB, ChoreoStyle.FESTIVAL_MAINSTAGE,
                126.0, 8, 0.06f, 0.82f, 0.28f, 0.06f, 0.65f, 1.15f,
                15f, 52f, 0.88f,
                MovementPattern.TILT_WAVE, MovementPattern.PAN_SWEEP, MovementPattern.FAN_SPREAD, MovementPattern.FAN_SPREAD,
                1.15f, 0.95f, 0.85f, 10.0f, true, true,
                0, 0, 0, 0.0f
        ));
        defaults.add(new AutoShowProfile(
                "show_03", "Deep Warehouse Techno", 2, MusicGenre.TECHNO, ChoreoStyle.DEEP_WAREHOUSE,
                135.0, 16, 0.04f, 0.88f, 0.22f, 0.08f, 0.72f, 1.40f,
                230f, 0f, 0.95f,
                MovementPattern.TILT_WAVE, MovementPattern.TILT_WAVE, MovementPattern.PAN_SWEEP, MovementPattern.PAN_SWEEP,
                1.10f, 0.80f, 0.60f, 8.0f, true, false,
                0, 0, 0, 0.0f
        ));
        defaults.add(new AutoShowProfile(
                "show_04", "Warm Sunset Club", 3, MusicGenre.HOUSE_CLUB, ChoreoStyle.SUNSET_GROOVE,
                122.0, 8, 0.08f, 0.75f, 0.30f, 0.05f, 0.64f, 1.05f,
                28f, 340f, 0.85f,
                MovementPattern.TILT_WAVE, MovementPattern.TILT_WAVE, MovementPattern.FAN_SPREAD, MovementPattern.FIGURE_8,
                0.90f, 0.75f, 0.50f, 5.0f, true, false,
                0, 0, 0, 0.0f
        ));
        defaults.add(new AutoShowProfile(
                "show_05", "146 BPM Schranz Snap", 4, MusicGenre.TECHNO, ChoreoStyle.SCHRANZ_STACCATO,
                146.0, 16, 0.03f, 0.94f, 0.18f, 0.10f, 0.85f, 1.65f,
                38f, 195f, 0.98f,
                MovementPattern.TILT_WAVE, MovementPattern.PAN_SWEEP, MovementPattern.FAN_SPREAD, MovementPattern.HARD_SNAP_STACCATO,
                1.50f, 1.00f, 0.90f, 10.0f, true, true,
                0, 0, 0, 0.0f
        ));
        return defaults;
    }

    public AutoShowProfile copyWithIdAndSlot(String newId, int newSlot, String newName) {
        return new AutoShowProfile(
                newId,
                newName,
                newSlot,
                referenceGenre,
                choreoStyle,
                referenceBpm,
                phraseLengthBars,
                learnedFloorEnergy,
                learnedPeakEnergy,
                breakdownThreshold,
                buildupSlopeThreshold,
                dropThreshold,
                bassDominanceRatio,
                primaryHueDeg,
                secondaryHueDeg,
                saturation,
                breakdownPattern,
                versePattern,
                buildupPattern,
                dropPattern,
                speedMultiplier,
                amplitude,
                phaseSpread,
                maxStrobeHz,
                blinderFlashOnKick,
                pyroOnDropBar,
                learnedBeats,
                completedPasses,
                detectedDrops,
                confidence
        );
    }

    public void applyStyle(ChoreoStyle style) {
        if (style == null) return;
        this.choreoStyle = style;
        this.primaryHueDeg = style.primaryHue();
        this.secondaryHueDeg = style.secondaryHue();
        this.saturation = style.saturation();
        this.versePattern = style.versePattern();
        this.dropPattern = style.dropPattern();
        this.maxStrobeHz = Math.clamp(style.dropStrobeHz(), 0.0f, MAX_SAFE_STROBE_HZ);
    }

    /**
     * Normalisiert einen Live-Energiewert relativ zur gelernten Dynamik-Spanne des Songs ({@code 0.0 .. 1.0}).
     */
    public float normalizeEnergy(float rawEnergy) {
        float span = Math.max(0.12f, learnedPeakEnergy - learnedFloorEnergy);
        return Math.clamp((rawEnergy - learnedFloorEnergy) / span, 0.0f, 1.0f);
    }

    public MovementPattern patternForSection(SongSection section) {
        return switch (section) {
            case AMBIENT_BREAKDOWN -> breakdownPattern;
            case VERSE_GROOVE -> versePattern;
            case RISING_BUILDUP -> buildupPattern;
            case PEAK_DROP -> dropPattern;
        };
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Deserialisiert und validiert/clampt alle Felder strikt (SEC-08 &amp; SEC-09).
     */
    public static AutoShowProfile fromJson(String json) {
        if (json == null || json.isBlank()) {
            return createDefaultSeedProfiles().getFirst();
        }
        try {
            AutoShowProfile raw = GSON.fromJson(json, AutoShowProfile.class);
            if (raw == null) {
                return createDefaultSeedProfiles().getFirst();
            }
            return new AutoShowProfile(
                    raw.profileId,
                    raw.displayName,
                    raw.slotIndex,
                    raw.referenceGenre,
                    raw.choreoStyle,
                    raw.referenceBpm,
                    raw.phraseLengthBars,
                    raw.learnedFloorEnergy,
                    raw.learnedPeakEnergy,
                    raw.breakdownThreshold,
                    raw.buildupSlopeThreshold,
                    raw.dropThreshold,
                    raw.bassDominanceRatio,
                    raw.primaryHueDeg,
                    raw.secondaryHueDeg,
                    raw.saturation,
                    raw.breakdownPattern,
                    raw.versePattern,
                    raw.buildupPattern,
                    raw.dropPattern,
                    raw.speedMultiplier,
                    raw.amplitude,
                    raw.phaseSpread,
                    raw.maxStrobeHz,
                    raw.blinderFlashOnKick,
                    raw.pyroOnDropBar,
                    raw.learnedBeats,
                    raw.completedPasses,
                    raw.detectedDrops,
                    raw.confidence
            );
        } catch (JsonSyntaxException ex) {
            return createDefaultSeedProfiles().getFirst();
        }
    }

    public long computeIntegrityCrc() {
        CRC32 crc = new CRC32();
        String payload = String.format(
                Locale.ROOT,
                "%s|%s|%d|%.2f|%.4f|%.4f|%.4f|%.2f|%d",
                profileId, displayName, slotIndex, referenceBpm,
                learnedFloorEnergy, learnedPeakEnergy, dropThreshold,
                maxStrobeHz, learnedBeats
        );
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static String sanitizeId(String raw) {
        if (raw == null || raw.isBlank()) return "show_01";
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (clean.length() > 24) clean = clean.substring(0, 24);
        return clean.isEmpty() ? "show_01" : clean;
    }

    public String profileId() {
        return profileId;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.trim();
        }
    }

    public int slotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = Math.clamp(slotIndex, 0, MAX_QUICK_SLOTS - 1);
    }

    public MusicGenre referenceGenre() {
        return referenceGenre;
    }

    public void setReferenceGenre(MusicGenre referenceGenre) {
        if (referenceGenre != null) this.referenceGenre = referenceGenre;
    }

    public ChoreoStyle choreoStyle() {
        return choreoStyle;
    }

    public double referenceBpm() {
        return referenceBpm;
    }

    public void setReferenceBpm(double referenceBpm) {
        this.referenceBpm = Math.clamp(referenceBpm, 60.0, 200.0);
    }

    public int phraseLengthBars() {
        return phraseLengthBars;
    }

    public void setPhraseLengthBars(int phraseLengthBars) {
        this.phraseLengthBars = Math.clamp(phraseLengthBars, 2, 32);
    }

    public float learnedFloorEnergy() {
        return learnedFloorEnergy;
    }

    public void setLearnedFloorEnergy(float learnedFloorEnergy) {
        this.learnedFloorEnergy = Math.clamp(learnedFloorEnergy, 0.0f, 0.50f);
    }

    public float learnedPeakEnergy() {
        return learnedPeakEnergy;
    }

    public void setLearnedPeakEnergy(float learnedPeakEnergy) {
        this.learnedPeakEnergy = Math.clamp(learnedPeakEnergy, 0.20f, 1.0f);
    }

    public float breakdownThreshold() {
        return breakdownThreshold;
    }

    public void setBreakdownThreshold(float breakdownThreshold) {
        this.breakdownThreshold = Math.clamp(breakdownThreshold, 0.10f, 0.55f);
    }

    public float buildupSlopeThreshold() {
        return buildupSlopeThreshold;
    }

    public void setBuildupSlopeThreshold(float buildupSlopeThreshold) {
        this.buildupSlopeThreshold = Math.clamp(buildupSlopeThreshold, 0.02f, 0.35f);
    }

    public float dropThreshold() {
        return dropThreshold;
    }

    public void setDropThreshold(float dropThreshold) {
        this.dropThreshold = Math.clamp(dropThreshold, 0.45f, 0.95f);
    }

    public float bassDominanceRatio() {
        return bassDominanceRatio;
    }

    public void setBassDominanceRatio(float bassDominanceRatio) {
        this.bassDominanceRatio = Math.clamp(bassDominanceRatio, 0.20f, 2.50f);
    }

    public float primaryHueDeg() {
        return primaryHueDeg;
    }

    public float secondaryHueDeg() {
        return secondaryHueDeg;
    }

    public float saturation() {
        return saturation;
    }

    public float speedMultiplier() {
        return speedMultiplier;
    }

    public void setSpeedMultiplier(float speedMultiplier) {
        this.speedMultiplier = Math.clamp(speedMultiplier, 0.25f, 3.0f);
    }

    public float amplitude() {
        return amplitude;
    }

    public void setAmplitude(float amplitude) {
        this.amplitude = Math.clamp(amplitude, 0.15f, 1.0f);
    }

    public float phaseSpread() {
        return phaseSpread;
    }

    public void setPhaseSpread(float phaseSpread) {
        this.phaseSpread = Math.clamp(phaseSpread, 0.0f, 1.0f);
    }

    public float maxStrobeHz() {
        return maxStrobeHz;
    }

    public void setMaxStrobeHz(float maxStrobeHz) {
        this.maxStrobeHz = Math.clamp(maxStrobeHz, 0.0f, MAX_SAFE_STROBE_HZ);
    }

    public boolean isBlinderFlashOnKick() {
        return blinderFlashOnKick;
    }

    public boolean isPyroOnDropBar() {
        return pyroOnDropBar;
    }

    public int learnedBeats() {
        return learnedBeats;
    }

    public void setLearnedBeats(int learnedBeats) {
        this.learnedBeats = Math.max(0, learnedBeats);
    }

    public int completedPasses() {
        return completedPasses;
    }

    public void setCompletedPasses(int completedPasses) {
        this.completedPasses = Math.max(0, completedPasses);
    }

    public int detectedDrops() {
        return detectedDrops;
    }

    public void setDetectedDrops(int detectedDrops) {
        this.detectedDrops = Math.max(0, detectedDrops);
    }

    public float confidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = Math.clamp(confidence, 0.0f, 1.0f);
    }
}
