package de.archer.admx.audio;

import de.archer.admx.AdmxPlugin.PlacedFixture;
import de.archer.admx.audio.AutoShowProfile.ChoreoStyle;
import de.archer.admx.audio.AutoShowProfile.SongSection;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Color;

/**
 * Intelligenter Auto-Show-Regisseur mit <b>Multi-Pass Song-DNA Learner</b> und
 * <b>3-Stufen Live-Beat-PLL "Idiotenschutz"-Synchronisation</b>.
 * <p>
 * Unterstützt drei Betriebsmodi:
 * <ol>
 *     <li><b>ADAPTIVE_LIVE (Standard):</b> Reagiert frei auf Live-BPM, Genre und Kick-Erkennung.</li>
 *     <li><b>LEARNING_PASS (Multi-Pass Lern-Algorithmus):</b> Analysiert das laufende Lied über
 *         mehrere Durchläufe, kalibriert das wahre Dynamik-Fenster ({@code floor} bis {@code peak}),
 *         eliminiert False-Drops, erkennt Buildup-Steigungen und baut ein {@link AutoShowProfile} auf.</li>
 *     <li><b>LOCKED_PROFILE (Gespeicherte Auto-Show mit Idiotenschutz):</b> Der Lern-Algorithmus ist
 *         eingefroren; ein gespeichertes {@link AutoShowProfile} steuert die Show. Dank Live-Beat-PLL
 *         ({@link BeatSnapshot#phase()}), Echtzeit-Sektions-Matching und Auto-Standby bei Musik-Stopp
 *         läuft die gespeicherte Show <b>immer 100 % synchron zur Musik</b> – egal wann der Nutzer auf Play drückt!</li>
 * </ol>
 */
public final class SmartAutoDirector {

    public enum DirectorMode {
        ADAPTIVE_LIVE("Adaptiv Live (Frei)"),
        LEARNING_PASS("Multi-Pass Lernen aktiv"),
        LOCKED_PROFILE("Gespeicherte Auto-Show (PLL-Sync)");

        private final String displayName;

        DirectorMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private volatile DirectorMode directorMode = DirectorMode.ADAPTIVE_LIVE;
    private volatile AutoShowProfile activeProfile = AutoShowProfile.createDefaultSeedProfiles().getFirst();
    private volatile int selectedQuickSlot = 0; // 0..7

    // Live-Zustand des Multi-Pass Learners & Section-Matchers (auf Main-Render-Thread aktualisiert)
    private long lastProcessedTick = -1L;
    private int lastBeatInBar = -1;
    private long lastBarCount = -1L;
    private float emaEnergyShort = 0.25f;
    private float emaEnergyLong = 0.25f;
    private float prevEnergyShort = 0.25f;
    private float observedMinEnergy = 0.35f;
    private float observedMaxEnergy = 0.45f;
    private boolean inDropCooldown = false;
    private volatile SongSection currentSection = SongSection.VERSE_GROOVE;
    private volatile boolean musicPausedStandby = false;
    private int sectionHoldBeats = 0;

    /**
     * Startet den Multi-Pass Lern-Algorithmus für den gewählten Quick-Slot.
     */
    public synchronized void startLearning(int targetSlot, String initialName) {
        this.selectedQuickSlot = Math.clamp(targetSlot, 0, AutoShowProfile.MAX_QUICK_SLOTS - 1);
        ChoreoStyle currentStyle = (activeProfile != null) ? activeProfile.choreoStyle() : ChoreoStyle.CYBERPUNK_RAVE;
        this.activeProfile = AutoShowProfile.createFreshLearningProfile(this.selectedQuickSlot, initialName, currentStyle);
        this.observedMinEnergy = 0.35f;
        this.observedMaxEnergy = 0.45f;
        this.inDropCooldown = false;
        this.sectionHoldBeats = 0;
        this.directorMode = DirectorMode.LEARNING_PASS;
    }

    /**
     * Schaltet den Lern-Algorithmus ab (friert die gelernte Song-DNA ein) und gibt das fertige
     * {@link AutoShowProfile} zum Speichern in SQLite zurück.
     */
    public synchronized AutoShowProfile stopLearningAndFreeze(String customName, int targetSlot) {
        int slot = Math.clamp(targetSlot, 0, AutoShowProfile.MAX_QUICK_SLOTS - 1);
        this.selectedQuickSlot = slot;
        String id = String.format(java.util.Locale.ROOT, "show_%02d", slot + 1);
        if (this.activeProfile == null) {
            this.activeProfile = AutoShowProfile.createFreshLearningProfile(slot, customName, ChoreoStyle.CYBERPUNK_RAVE);
        }
        String finalName = (customName != null && !customName.isBlank())
                ? customName.trim()
                : this.activeProfile.displayName();

        if (this.activeProfile.learnedBeats() > 8 && this.activeProfile.confidence() < 0.85f) {
            this.activeProfile.setConfidence(0.85f);
        }
        if (this.activeProfile.completedPasses() < 1 && this.activeProfile.learnedBeats() >= 16) {
            this.activeProfile.setCompletedPasses(1);
        }

        this.activeProfile = this.activeProfile.copyWithIdAndSlot(id, slot, finalName);
        this.directorMode = DirectorMode.LOCKED_PROFILE;
        return this.activeProfile;
    }

    /**
     * Lädt ein gespeichertes {@link AutoShowProfile} in den gesperrten Wiedergabe-Modus
     * mit aktivem 3-Stufen Live-Beat-PLL "Idiotenschutz".
     */
    public synchronized void recallProfile(AutoShowProfile profile) {
        if (profile == null) return;
        this.activeProfile = profile;
        this.selectedQuickSlot = profile.slotIndex();
        this.directorMode = DirectorMode.LOCKED_PROFILE;
        this.sectionHoldBeats = 0;
    }

    /**
     * Schaltet zurück in den freien, unkalibrierten Adaptive-Live-Modus.
     */
    public synchronized void setAdaptiveLiveMode() {
        this.directorMode = DirectorMode.ADAPTIVE_LIVE;
    }

    /**
     * Variiert den Choreografie-Stil (Farben, Bewegungsmuster, Strobe-Intensität), während
     * die gelernten Audio-Schwellenwerte erhalten bleiben.
     */
    public synchronized ChoreoStyle mutateChoreoStyle() {
        if (this.activeProfile == null) {
            this.activeProfile = AutoShowProfile.createDefaultSeedProfiles().getFirst();
        }
        ChoreoStyle next = this.activeProfile.choreoStyle().next();
        this.activeProfile.applyStyle(next);
        return next;
    }

    /**
     * Passt die Drop-Auslöseschwelle des aktuellen Profils fein an (+/-).
     */
    public synchronized float adjustDropThreshold(float delta) {
        if (this.activeProfile == null) return 0.68f;
        float next = Math.clamp(this.activeProfile.dropThreshold() + delta, 0.45f, 0.92f);
        this.activeProfile.setDropThreshold(next);
        return next;
    }

    public DirectorMode directorMode() {
        return directorMode;
    }

    public AutoShowProfile activeProfile() {
        return activeProfile;
    }

    public int selectedQuickSlot() {
        return selectedQuickSlot;
    }

    public void setSelectedQuickSlot(int slot) {
        this.selectedQuickSlot = Math.clamp(slot, 0, AutoShowProfile.MAX_QUICK_SLOTS - 1);
    }

    public SongSection currentSection() {
        return currentSection;
    }

    public boolean isMusicPausedStandby() {
        return musicPausedStandby;
    }

    /**
     * Führt einmal pro Server-Tick das Multi-Pass-Lernen und die 3-Stufen-Sektions-Erkennung aus.
     */
    private void updateDirectorOncePerTick(long tickCounter, BeatSnapshot beat) {
        if (tickCounter == lastProcessedTick) {
            return;
        }
        lastProcessedTick = tickCounter;

        // Schutzstufe 3 (Idiotenschutz): Auto-Hold bei Musik-Pause / Stille
        boolean silent = beat.energy() < 0.025f && !beat.isKickDriving() && !beat.isKick();
        this.musicPausedStandby = silent;
        if (silent) {
            this.currentSection = SongSection.AMBIENT_BREAKDOWN;
            return;
        }

        float rawEnergy = beat.energy();
        this.prevEnergyShort = this.emaEnergyShort;
        this.emaEnergyShort = this.emaEnergyShort * 0.78f + rawEnergy * 0.22f;
        this.emaEnergyLong = this.emaEnergyLong * 0.96f + rawEnergy * 0.04f;
        float energyRiseSlope = Math.max(0.0f, this.emaEnergyShort - this.prevEnergyShort);

        boolean newBeat = (beat.beatInBar() != this.lastBeatInBar) || (beat.barCount() != this.lastBarCount);
        this.lastBeatInBar = beat.beatInBar();
        this.lastBarCount = beat.barCount();

        AutoShowProfile profile = this.activeProfile;
        if (profile == null) {
            return;
        }

        // --- MODUS A: MULTI-PASS LEARNING AKTIV ---
        if (this.directorMode == DirectorMode.LEARNING_PASS) {
            if (this.emaEnergyShort < this.observedMinEnergy) {
                this.observedMinEnergy = this.observedMinEnergy * 0.90f + this.emaEnergyShort * 0.10f;
            } else {
                this.observedMinEnergy = Math.min(0.40f, this.observedMinEnergy + 0.0001f);
            }
            if (this.emaEnergyShort > this.observedMaxEnergy) {
                this.observedMaxEnergy = this.observedMaxEnergy * 0.85f + this.emaEnergyShort * 0.15f;
            }

            profile.setLearnedFloorEnergy(Math.clamp(this.observedMinEnergy, 0.02f, 0.40f));
            profile.setLearnedPeakEnergy(Math.clamp(Math.max(this.observedMaxEnergy, this.observedMinEnergy + 0.22f), 0.25f, 1.0f));
            profile.setReferenceBpm(profile.referenceBpm() * 0.95 + beat.bpm() * 0.05);
            profile.setReferenceGenre(beat.genre());

            if (newBeat) {
                int beats = profile.learnedBeats() + 1;
                profile.setLearnedBeats(beats);
                if (this.sectionHoldBeats > 0) {
                    this.sectionHoldBeats--;
                }

                // Ein "Pass" entspricht 64 Beats (16 Takten); mit jedem Pass steigt die Präzision
                int passes = beats / 64;
                if (passes > profile.completedPasses()) {
                    profile.setCompletedPasses(passes);
                    float refinedDrop = Math.clamp(0.66f + Math.min(3, passes) * 0.025f, 0.65f, 0.78f);
                    profile.setDropThreshold(refinedDrop);
                }

                float beatConf = Math.min(1.0f, beats / 128.0f);
                float dynSpreadConf = Math.clamp((profile.learnedPeakEnergy() - profile.learnedFloorEnergy()) / 0.35f, 0.2f, 1.0f);
                profile.setConfidence(Math.clamp(beatConf * 0.75f + dynSpreadConf * 0.25f, 0.05f, 1.0f));
            }
        } else if (newBeat && this.sectionHoldBeats > 0) {
            this.sectionHoldBeats--;
        }

        // --- SCHUTZSTUFE 2 (IDIOTENSCHUTZ): ECHTZEIT-SEKTIONS-ERKENNUNG (AUCH MITTEN IM SONG) ---
        float normEnergy = profile.normalizeEnergy(Math.max(this.emaEnergyShort, beat.energy() * 0.9f));
        boolean strongKickDrive = (beat.isKickDriving() || beat.dropImpact()) && beat.energy() >= 0.38f;
        boolean risingBuildup = (energyRiseSlope >= profile.buildupSlopeThreshold() * 0.35f)
                || (normEnergy > profile.breakdownThreshold() && !beat.isKickDriving() && beat.strobeIntensity() > 0.35f);

        SongSection targetSection;
        if ((normEnergy >= profile.dropThreshold() && strongKickDrive) || beat.dropImpact()) {
            targetSection = SongSection.PEAK_DROP;
            if (this.directorMode == DirectorMode.LEARNING_PASS && !this.inDropCooldown) {
                profile.setDetectedDrops(profile.detectedDrops() + 1);
                this.inDropCooldown = true;
            }
        } else if (normEnergy <= profile.breakdownThreshold() && !beat.isKickDriving()) {
            targetSection = SongSection.AMBIENT_BREAKDOWN;
            this.inDropCooldown = false;
        } else if (risingBuildup) {
            targetSection = SongSection.RISING_BUILDUP;
            this.inDropCooldown = false;
        } else {
            targetSection = SongSection.VERSE_GROOVE;
            if (normEnergy < profile.dropThreshold() - 0.12f) {
                this.inDropCooldown = false;
            }
        }

        if (targetSection != this.currentSection) {
            if (targetSection == SongSection.PEAK_DROP && (beat.isKick() || beat.dropImpact())) {
                this.currentSection = SongSection.PEAK_DROP;
                this.sectionHoldBeats = 8;
            } else if (this.sectionHoldBeats <= 0 && (beat.beatInBar() == 0 || targetSection == SongSection.AMBIENT_BREAKDOWN)) {
                this.currentSection = targetSection;
                this.sectionHoldBeats = 4;
            }
        }
    }

    /**
     * Berechnet den Zielzustand eines Geräts im {@code AUTO_SHOW}-Modus.
     */
    public FixtureState computeState(
            PlacedFixture fixture,
            int totalInGroup,
            long tickCounter,
            BeatSnapshot beat
    ) {
        updateDirectorOncePerTick(tickCounter, beat);

        if (this.directorMode == DirectorMode.LEARNING_PASS || this.directorMode == DirectorMode.LOCKED_PROFILE) {
            return computeProfileDrivenState(fixture, totalInGroup, tickCounter, beat, this.activeProfile);
        }

        return computeAdaptiveLiveState(fixture, totalInGroup, tickCounter, beat);
    }

    /**
     * Berechnet die Lichtshow anhand der gelernten oder gespeicherten {@link AutoShowProfile} Song-DNA.
     * <p>
     * <b>Schutzstufe 1 (Idiotenschutz):</b> Alle Bewegungen, Blitze und Farbwechsel nutzen ausschließlich
     * die Live-Beat-Phase ({@link BeatSnapshot#phase()}) und den Live-Taktzähler als Phasen-Lock!
     */
    private FixtureState computeProfileDrivenState(
            PlacedFixture fixture,
            int totalInGroup,
            long tickCounter,
            BeatSnapshot beat,
            AutoShowProfile profile
    ) {
        FixtureType type = fixture.type();
        int index = fixture.fixtureOrder();
        float normIndex = totalInGroup > 1 ? (float) index / (totalInGroup - 1) : 0.5f;
        float symOffset = (normIndex - 0.5f) * 2.0f; // [-1.0 .. +1.0]
        float phaseF = (float) beat.phase();

        // Schutzstufe 3: Musik pausiert -> ruhiger Stage-Glow ohne Geblinke
        if (this.musicPausedStandby) {
            Oklab standbyCol = Oklab.fromHSB(profile.primaryHueDeg(), profile.saturation() * 0.75f, 0.45f);
            if (type.isFogMachine()) {
                return new FixtureState(0f, 0f, 0.35f, type.fogType().baseColor(), 30f, 0f);
            }
            if (type == FixtureType.STAGE_FLAME || type == FixtureType.STROBE_BAR || type == FixtureType.BLINDER_2LITE) {
                return new FixtureState(0f, -15f, 0f, standbyCol, 50f, 0f);
            }
            double idleWave = ((double) (tickCounter % 200_000L) * 0.02) + index * 0.5;
            float idlePan = (float) Math.sin(idleWave) * 35f * symOffset;
            return new FixtureState(idlePan, -18f, 0.32f, standbyCol, 32f, 0f);
        }

        SongSection section = this.currentSection;
        MovementPattern pattern = profile.patternForSection(section);

        // Schutzstufe 1: Phase-Locked Loop (PLL) direkt auf Live-Beat-Phase (0.0 .. 1.0) + Takt-Index!
        double continuousBeats = (beat.barCount() * 4.0) + beat.beatInBar() + beat.phase();
        double fanOffsetRad = symOffset * profile.phaseSpread() * Math.PI * 0.85;
        double speedScale = switch (section) {
            case AMBIENT_BREAKDOWN -> 0.35 * profile.speedMultiplier();
            case VERSE_GROOVE -> 0.75 * profile.speedMultiplier();
            case RISING_BUILDUP -> 1.15 * profile.speedMultiplier();
            case PEAK_DROP -> 1.50 * profile.speedMultiplier();
        };
        double phaseRad = (continuousBeats * Math.PI * 0.5 * speedScale + fanOffsetRad) % (2.0 * Math.PI);

        float ampScale = switch (section) {
            case AMBIENT_BREAKDOWN -> 0.45f * profile.amplitude();
            case VERSE_GROOVE -> 0.75f * profile.amplitude();
            case RISING_BUILDUP -> 0.90f * profile.amplitude();
            case PEAK_DROP -> 1.00f * profile.amplitude();
        };

        float pan = 0f;
        float tilt = -12f;
        switch (pattern) {
            case STATIC -> {
                pan = symOffset * 45f * ampScale;
                tilt = -12f;
            }
            case PAN_SWEEP -> {
                pan = (float) Math.sin(phaseRad) * 115f * ampScale;
                tilt = -10f + (float) Math.cos(phaseRad * 0.5) * 18f * ampScale;
            }
            case TILT_WAVE -> {
                pan = symOffset * 55f * ampScale;
                tilt = -10f + (float) Math.sin(phaseRad) * 38f * ampScale;
            }
            case CIRCLE -> {
                pan = (float) Math.sin(phaseRad) * 105f * ampScale;
                tilt = -8f + (float) Math.cos(phaseRad) * 34f * ampScale;
            }
            case FIGURE_8 -> {
                pan = (float) Math.sin(phaseRad) * 115f * ampScale;
                tilt = -8f + (float) Math.sin(phaseRad * 2.0) * 32f * ampScale;
            }
            case FAN_SPREAD -> {
                float openFactor = (section == SongSection.RISING_BUILDUP)
                        ? (0.35f + 0.65f * phaseF)
                        : (0.5f + 0.5f * (float) Math.sin(phaseRad));
                pan = symOffset * 125f * openFactor * ampScale;
                tilt = -22f + openFactor * 36f * ampScale;
            }
            case HARD_SNAP_STACCATO -> {
                // Staccato-Snap: Kopf springt schlagartig auf Beat-Onset auf neue Position und bleibt komplett stehen
                long beatStep = (beat.barCount() * 4L) + beat.beatInBar();
                float[] snapPan = {-70f, 0f, 70f, 0f, -55f, 55f, -90f, 90f};
                float[] snapTilt = {-28f, 42f, -28f, 58f, -14f, -14f, 32f, 32f};
                int idx = Math.abs((int) ((beatStep + index) % snapPan.length));
                pan = (snapPan[idx] * ampScale) + (symOffset * 40f * profile.phaseSpread());
                tilt = snapTilt[idx] * ampScale;
            }
            case CLIFFHANGER_FREEZE -> {
                // Cliffhanger Schock-Freeze: Friert alle Köpfe sofort starr in dramatischer Center-Spannung ein
                pan = symOffset * 35f * profile.phaseSpread();
                tilt = 42f * ampScale;
            }
            case CINEMATIC_STOMP_SNAP -> {
                long beatStep = (beat.barCount() * 4L) + beat.beatInBar();
                float[] stompPan = {-80f, 80f, 0f, -65f, 65f, 0f};
                float[] stompTilt = {48f, 48f, -25f, 55f, 55f, 15f};
                int idx = Math.abs((int) ((beatStep + index) % stompPan.length));
                pan = (stompPan[idx] * ampScale) + (symOffset * 30f * profile.phaseSpread());
                tilt = stompTilt[idx] * ampScale;
            }
        }

        // Farbwahl aus der gelernten Song-DNA (Takt-synchroner Wechsel zwischen Primär- und Sekundär-Farbe)
        int phraseBeats = Math.max(4, profile.phraseLengthBars() * 4);
        boolean useSecondary = (((beat.barCount() * 4L) + beat.beatInBar()) % phraseBeats) >= (phraseBeats / 2);
        if (section == SongSection.PEAK_DROP && (index % 2 == (beat.beatInBar() % 2))) {
            useSecondary = !useSecondary;
        }
        float baseHue = useSecondary ? profile.secondaryHueDeg() : profile.primaryHueDeg();
        Oklab primaryCol = Oklab.fromHSB(baseHue, profile.saturation(), 1.0f);
        Oklab accentCol = Oklab.fromHSB((baseHue + 45f) % 360f, Math.min(1.0f, profile.saturation() + 0.1f), 1.0f);

        float safeStrobe = (section == SongSection.PEAK_DROP && beat.strobeActive())
                ? Math.min(AutoShowProfile.MAX_SAFE_STROBE_HZ, profile.maxStrobeHz())
                : 0.0f;

        return switch (type) {
            case MOVING_HEAD_SPOT -> {
                float dim = switch (section) {
                    case AMBIENT_BREAKDOWN -> 0.45f;
                    case VERSE_GROOVE -> 0.78f + (beat.isKick() ? 0.22f : 0f);
                    case RISING_BUILDUP -> 0.65f + 0.35f * phaseF;
                    case PEAK_DROP -> 1.0f;
                };
                float zoom = (section == SongSection.PEAK_DROP) ? 16f : 26f;
                yield new FixtureState(pan, tilt, dim, primaryCol, zoom, safeStrobe * 0.5f);
            }
            case MOVING_HEAD_BEAM -> {
                float dim = (section == SongSection.AMBIENT_BREAKDOWN) ? 0.30f : (0.85f + (beat.isKick() ? 0.15f : 0f));
                yield new FixtureState(pan * 1.1f, tilt, dim, accentCol, 4f, safeStrobe);
            }
            case MOVING_HEAD_WASH -> {
                float dim = 0.55f + 0.45f * profile.normalizeEnergy(beat.energy());
                yield new FixtureState(pan * 0.75f, tilt * 0.75f, Math.clamp(dim, 0.35f, 1.0f), primaryCol, 54f, 0f);
            }
            case LASER_FAN_PROJECTOR, LASER_BEAM_PROJECTOR, MOVING_LASER_HEAD -> {
                if (section == SongSection.AMBIENT_BREAKDOWN) {
                    yield new FixtureState(0f, -10f, 0f, accentCol, 30f, 0f);
                }
                float fanAngle = (type == FixtureType.LASER_BEAM_PROJECTOR) ? 4f : (38f + beat.energy() * 28f);
                float lDim = (section == SongSection.PEAK_DROP) ? 1.0f : 0.75f;
                yield new FixtureState(pan * 0.85f, tilt * 0.8f, lDim, accentCol, fanAngle, safeStrobe);
            }
            case BLINDER_2LITE -> {
                boolean flash = profile.isBlinderFlashOnKick()
                        && ((section == SongSection.PEAK_DROP && beat.isKick())
                        || (section == SongSection.RISING_BUILDUP && beat.beatInBar() == 3 && phaseF > 0.5f));
                float dim = flash ? 1.0f : (section == SongSection.PEAK_DROP ? 0.25f * (1.0f - phaseF) : 0.0f);
                yield new FixtureState(0f, -15f, dim, Oklab.fromRGB(Color.fromRGB(255, 214, 155)), 65f, 0f);
            }
            case STROBE_BAR -> {
                if (section == SongSection.PEAK_DROP && safeStrobe > 0.5f) {
                    yield new FixtureState(0f, -20f, 1.0f, Oklab.fromRGB(Color.WHITE), 55f, safeStrobe);
                }
                if (section == SongSection.RISING_BUILDUP && beat.energy() > 0.45f) {
                    yield new FixtureState(0f, -20f, 0.75f, primaryCol, 55f, Math.min(6.0f, profile.maxStrobeHz()));
                }
                yield new FixtureState(0f, -20f, 0f, Oklab.fromRGB(Color.WHITE), 50f, 0f);
            }
            case FOG_MACHINE_HAZER, FOG_MACHINE_LOW_FOG -> {
                // PERF-03: Nebelmaschinen niemals stroben!
                float fogDim = (section == SongSection.RISING_BUILDUP || section == SongSection.PEAK_DROP) ? 0.95f : 0.55f;
                yield new FixtureState(0f, 0f, fogDim, type.fogType().baseColor(), 30f, 0f);
            }
            case STAGE_FLAME -> {
                boolean fire = profile.isPyroOnDropBar()
                        && section == SongSection.PEAK_DROP
                        && beat.beatInBar() == 0
                        && phaseF < 0.40f;
                yield new FixtureState(0f, 0f, fire ? 1.0f : 0.0f, Oklab.fromRGB(Color.fromRGB(255, 130, 20)), 20f, 0f);
            }
        };
    }

    private FixtureState computeAdaptiveLiveState(
            PlacedFixture fixture,
            int totalInGroup,
            long tickCounter,
            BeatSnapshot beat
    ) {
        FixtureType type = fixture.type();
        MusicGenre genre = beat.genre();
        int index = fixture.fixtureOrder();
        float normIndex = totalInGroup > 1 ? (float) index / (totalInGroup - 1) : 0.5f;
        float symOffset = (normIndex - 0.5f) * 2.0f; // [-1.0 .. +1.0]

        // Harmonische Farbpalette je nach erkanntem Genre & Takt-Zähler
        int barIdx = (int) (beat.barCount() & 0x7FFF_FFFFL);
        Oklab primaryColor = computeGenreColor(genre, barIdx, beat.beatInBar(), false, tickCounter);
        Oklab secondaryColor = computeGenreColor(genre, barIdx, beat.beatInBar(), true, tickCounter);

        // Kontinuierliche Beat-Phase für weiche, temposynchrone Bewegungen
        double continuousBeat = (beat.barCount() * 4.0) + beat.beatInBar() + beat.phase();
        double fanPhase = continuousBeat * 0.5 * Math.PI + (index * 0.45);

        return switch (type) {
            case MOVING_HEAD_SPOT -> computeSpotState(genre, beat, symOffset, fanPhase, primaryColor);
            case MOVING_HEAD_BEAM -> computeBeamState(genre, beat, index, symOffset, fanPhase, secondaryColor);
            case MOVING_HEAD_WASH -> computeWashState(genre, beat, symOffset, fanPhase, primaryColor);
            case LASER_FAN_PROJECTOR -> computeLaserFanState(genre, beat, symOffset, fanPhase, secondaryColor);
            case LASER_BEAM_PROJECTOR -> computeLaserNeedleState(genre, beat, index, symOffset, fanPhase, secondaryColor);
            case MOVING_LASER_HEAD -> computeMovingLaserState(genre, beat, symOffset, fanPhase, secondaryColor);
            case BLINDER_2LITE -> computeBlinderState(genre, beat);
            case STROBE_BAR -> computeStrobeBarState(genre, beat);
            case FOG_MACHINE_HAZER, FOG_MACHINE_LOW_FOG -> computeFogState(type, beat);
            case STAGE_FLAME -> computeFlameState(genre, beat, index, totalInGroup);
        };
    }

    private FixtureState computeSpotState(MusicGenre genre, BeatSnapshot beat, float symOffset, double fanPhase, Oklab color) {
        float panAmp = switch (genre) {
            case DRUM_AND_BASS -> 125f;
            case TECHNO, HOUSE_CLUB -> 95f;
            case RAP_HIPHOP -> 65f;
            case AMBIENT -> 45f;
        };
        float speedMul = (genre == MusicGenre.DRUM_AND_BASS) ? 1.5f : (genre == MusicGenre.AMBIENT ? 0.35f : 0.85f);
        float pan = (float) Math.sin(fanPhase * speedMul) * panAmp;
        float tilt = (float) Math.cos(fanPhase * speedMul * 0.75) * 32f - 10f + (beat.isKick() ? 8f : 0f);
        float dimmer = 0.65f + 0.35f * Math.min(1.0f, beat.energy() * 1.4f);
        float baseZoom = (genre == MusicGenre.RAP_HIPHOP || genre == MusicGenre.AMBIENT) ? 34f : 18f;
        float zoom = baseZoom + (1.0f - (float) beat.phase()) * 10f;
        return new FixtureState(pan, tilt, dimmer, color, zoom, 0f);
    }

    private FixtureState computeBeamState(MusicGenre genre, BeatSnapshot beat, int index, float symOffset, double fanPhase, Oklab color) {
        boolean oddFixture = (index % 2) == 1;
        boolean oddBeat = (beat.beatInBar() % 2) == 1;
        float beatFlip = (oddFixture ^ oddBeat) ? 1.0f : -1.0f;

        float pan;
        float tilt;
        if (genre == MusicGenre.TECHNO || genre == MusicGenre.DRUM_AND_BASS) {
            pan = symOffset * 75f + beatFlip * 28f * (float) Math.sin(fanPhase);
            tilt = (oddFixture ^ oddBeat) ? 15f : -25f;
        } else {
            pan = (float) Math.sin(fanPhase + 0.8) * 90f;
            tilt = (float) Math.cos(fanPhase + 0.8) * 30f - 8f;
        }

        float dimmer = beat.isKickDriving()
                ? ((oddFixture ^ oddBeat) ? 1.0f : 0.35f + 0.65f * (1.0f - (float) beat.phase()))
                : 0.70f;
        float strobe = (beat.strobeActive() && genre == MusicGenre.DRUM_AND_BASS) ? beat.strobeHz() * 0.5f : 0f;
        return new FixtureState(pan, tilt, dimmer, color, 4f, strobe);
    }

    private FixtureState computeWashState(MusicGenre genre, BeatSnapshot beat, float symOffset, double fanPhase, Oklab color) {
        float pan = symOffset * 55f + (float) Math.sin(fanPhase * 0.5) * 35f;
        float tilt = -14f + (float) Math.cos(fanPhase * 0.5) * 18f;
        float dimmer = 0.50f + 0.50f * Math.min(1.0f, beat.energy() * 1.2f);
        float zoom = 48f + beat.energy() * 16f;
        return new FixtureState(pan, tilt, dimmer, color, zoom, 0f);
    }

    private FixtureState computeLaserFanState(MusicGenre genre, BeatSnapshot beat, float symOffset, double fanPhase, Oklab color) {
        if (!beat.isKickDriving() && beat.energy() < 0.28f && genre != MusicGenre.AMBIENT) {
            return new FixtureState(0f, -10f, 0.0f, color, 30f, 0f);
        }
        float pan = (float) Math.sin(fanPhase * 0.7) * 20f;
        float tilt = -6f + (float) Math.sin(fanPhase * 1.4) * 20f;
        float fanAngle = 36f + beat.energy() * 28f;
        float strobe = beat.strobeActive() ? beat.strobeHz() : 0f;
        return new FixtureState(pan, tilt, 0.92f, color, fanAngle, strobe);
    }

    private FixtureState computeLaserNeedleState(MusicGenre genre, BeatSnapshot beat, int index, float symOffset, double fanPhase, Oklab color) {
        if (!beat.isKickDriving() && beat.energy() < 0.32f) {
            return new FixtureState(0f, 0f, 0.0f, color, 4f, 0f);
        }
        float stepPan = symOffset * 45f + ((beat.beatInBar() - 1.5f) * 18f);
        float stepTilt = -5f + ((index % 2 == 0 ? 1 : -1) * (beat.isKick() ? 18f : -8f));
        float strobe = beat.strobeActive() ? beat.strobeHz() : 0f;
        return new FixtureState(stepPan, stepTilt, 0.98f, color, 4f, strobe);
    }

    private FixtureState computeMovingLaserState(MusicGenre genre, BeatSnapshot beat, float symOffset, double fanPhase, Oklab color) {
        if (!beat.isKickDriving() && beat.energy() < 0.25f) {
            return new FixtureState(0f, -10f, 0.0f, color, 30f, 0f);
        }
        float pan = (float) Math.sin(fanPhase * 1.1) * 115f;
        float tilt = (float) Math.cos(fanPhase * 1.3) * 32f - 6f;
        float fanAngle = 38f + beat.energy() * 24f;
        float strobe = beat.strobeActive() ? beat.strobeHz() : 0f;
        return new FixtureState(pan, tilt, 0.95f, color, fanAngle, strobe);
    }

    private FixtureState computeBlinderState(MusicGenre genre, BeatSnapshot beat) {
        float dimmer = 0.0f;
        if (beat.isKick() && beat.energy() > 0.52f) {
            dimmer = Math.min(1.0f, beat.energy() * 1.15f);
        } else if (beat.beatInBar() == 0 && beat.isKickDriving() && beat.phase() < 0.30) {
            dimmer = (1.0f - ((float) beat.phase() / 0.30f)) * 0.90f;
        }
        return new FixtureState(0f, -15f, dimmer, Oklab.fromRGB(Color.fromRGB(255, 210, 145)), 65f, 0f);
    }

    private FixtureState computeStrobeBarState(MusicGenre genre, BeatSnapshot beat) {
        if (!beat.strobeActive()) {
            return new FixtureState(0f, -20f, 0.0f, Oklab.fromRGB(Color.WHITE), 50f, 0f);
        }
        return new FixtureState(0f, -20f, 1.0f, Oklab.fromRGB(Color.WHITE), 55f, beat.strobeHz());
    }

    private FixtureState computeFogState(FixtureType type, BeatSnapshot beat) {
        float dimmer = 0.45f + 0.55f * Math.min(1.0f, beat.energy() * 1.2f);
        return new FixtureState(0f, 0f, dimmer, type.fogType().baseColor(), 30f, 0f);
    }

    private FixtureState computeFlameState(MusicGenre genre, BeatSnapshot beat, int index, int totalInGroup) {
        boolean fire = false;
        if (beat.isKickDriving() && beat.energy() > 0.62f) {
            if (beat.beatInBar() == 0 && beat.phase() < 0.35) {
                fire = true;
            } else if (genre == MusicGenre.DRUM_AND_BASS || genre == MusicGenre.TECHNO) {
                int activeSlot = beat.beatInBar() % Math.max(1, totalInGroup);
                fire = (index == activeSlot) && beat.phase() < 0.40;
            }
        }
        return new FixtureState(0f, 0f, fire ? 1.0f : 0.0f, Oklab.fromRGB(Color.fromRGB(255, 135, 20)), 20f, 0f);
    }

    private Oklab computeGenreColor(MusicGenre genre, int barCount, int beatInBar, boolean secondary, long tickCounter) {
        int phraseIndex = (barCount / 2) % 4;
        float hue = switch (genre) {
            case TECHNO -> {
                float[] hues = secondary ? new float[]{190f, 225f, 355f, 180f} : new float[]{215f, 195f, 240f, 0f};
                yield hues[phraseIndex];
            }
            case RAP_HIPHOP -> {
                float[] hues = secondary ? new float[]{285f, 35f, 15f, 310f} : new float[]{42f, 12f, 280f, 30f};
                yield hues[phraseIndex];
            }
            case DRUM_AND_BASS -> {
                float[] hues = secondary ? new float[]{315f, 125f, 55f, 280f} : new float[]{120f, 305f, 270f, 140f};
                yield hues[(barCount + beatInBar) % 4];
            }
            case HOUSE_CLUB -> {
                float[] hues = secondary ? new float[]{185f, 325f, 45f, 200f} : new float[]{22f, 335f, 175f, 40f};
                yield hues[phraseIndex];
            }
            case AMBIENT -> (float) ((tickCounter * 0.4 + (secondary ? 60.0 : 0.0)) % 360.0);
        };
        float sat = (genre == MusicGenre.TECHNO && phraseIndex == 1 && !secondary) ? 0.25f : 0.90f;
        return Oklab.fromHSB(hue, sat, 1.0f);
    }
}
