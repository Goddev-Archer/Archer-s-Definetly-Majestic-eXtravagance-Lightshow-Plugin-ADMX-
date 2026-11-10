package de.archer.admx.control;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * Repräsentiert eine adressierbare DMX-Gerätegruppe in ADMX.
 * Jedes Gerät wird vor dem Spawnen einer Gruppe zugewiesen und erhält innerhalb der Gruppe
 * einen eindeutigen Ordnungs-Index, sodass sowohl Gruppen-Steuerung als auch phasenversetzte
 * Einzelgeräte-Effekte (Fanning / Chases) möglich sind.
 */
public final class FixtureGroup {

    /**
     * Maximale Strobe-Frequenz in Hz zum Schutz vor Photosensitivität / PSE (SEC-09).
     */
    public static final float MAX_SAFE_STROBE_HZ = 10.0f;

    private final String groupId;
    private String displayName;
    private Material iconMaterial;
    private int accentRgb;

    private ControlMode controlMode;
    private float dimmer;
    private float panDegrees;
    private float tiltDegrees;
    private float zoomDegrees;
    private float strobeHz;
    private float hueDegrees;
    private float saturation;
    private float brightness;

    private MovementPattern movementPattern;
    private float movementSpeed;
    private float movementAmplitude;
    private float phaseSpread;
    private de.archer.admx.audio.SoundToLightConfig soundConfig;

    public FixtureGroup(
            String groupId,
            String displayName,
            Material iconMaterial,
            int accentRgb,
            ControlMode controlMode,
            float dimmer,
            float panDegrees,
            float tiltDegrees,
            float zoomDegrees,
            float strobeHz,
            float hueDegrees,
            float saturation,
            float brightness,
            MovementPattern movementPattern,
            float movementSpeed,
            float movementAmplitude,
            float phaseSpread
    ) {
        this.groupId = groupId;
        this.displayName = displayName != null ? displayName : groupId;
        this.iconMaterial = iconMaterial != null ? iconMaterial : Material.CYAN_STAINED_GLASS;
        this.accentRgb = accentRgb;
        this.controlMode = controlMode != null ? controlMode : ControlMode.MANUAL_LIVE;
        this.dimmer = clampFinite(dimmer, 0f, 1f, 0.9f);
        this.panDegrees = clampFinite(panDegrees, -180f, 180f, 0f);
        this.tiltDegrees = clampFinite(tiltDegrees, -90f, 90f, -15f);
        this.zoomDegrees = clampFinite(zoomDegrees, 4f, 70f, 24f);
        this.strobeHz = clampFinite(strobeHz, 0f, MAX_SAFE_STROBE_HZ, 0f);
        this.hueDegrees = normalizeHue(hueDegrees);
        this.saturation = clampFinite(saturation, 0f, 1f, 0.85f);
        this.brightness = clampFinite(brightness, 0f, 1f, 1.0f);
        this.movementPattern = movementPattern != null ? movementPattern : MovementPattern.PAN_SWEEP;
        this.movementSpeed = clampFinite(movementSpeed, 0.1f, 4.0f, 1.0f);
        this.movementAmplitude = clampFinite(movementAmplitude, 0f, 1f, 0.5f);
        this.phaseSpread = clampFinite(phaseSpread, 0f, 1f, 0.5f);
        this.soundConfig = de.archer.admx.audio.SoundToLightConfig.defaultForGroup(this.groupId);
    }

    private static float clampFinite(float val, float min, float max, float fallback) {
        if (!Float.isFinite(val)) {
            return fallback;
        }
        return Math.clamp(val, min, max);
    }

    private static float normalizeHue(float hue) {
        if (!Float.isFinite(hue)) {
            return 0f;
        }
        return ((hue % 360f) + 360f) % 360f;
    }

    /**
     * Bühnentechnische Rigging-Zonen zur realistischen Gruppierung von Traversen und Boden-Sets.
     */
    public enum StageZone {
        UPPER_TRUSS("🏗 Obere Traversen (Upper Rigging)", NamedTextColor.AQUA),
        MAIN_TRUSS("🎪 Hauptbühnen-Traversen (Main Grid)", NamedTextColor.BLUE),
        SIDE_TOWER("🗼 Seitentürme & Kanzel (Side Towers)", NamedTextColor.GREEN),
        FLOOR_PACKAGE("📐 Boden-Set & Rampe (Ground Package)", NamedTextColor.GOLD),
        SPECIAL_FX("💥 Spezial-Effekte & Show-Laser", NamedTextColor.LIGHT_PURPLE);

        private final String displayName;
        private final NamedTextColor color;

        StageZone(String displayName, NamedTextColor color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String displayName() {
            return displayName;
        }

        public NamedTextColor color() {
            return color;
        }
    }

    /**
     * Ermittelt die bühnentechnische Zone dieser Gruppe.
     */
    public StageZone stageZone() {
        return stageZoneFor(this.groupId);
    }

    public static StageZone stageZoneFor(String groupId) {
        if (groupId == null) return StageZone.MAIN_TRUSS;
        String id = groupId.toLowerCase(Locale.ROOT);
        if (id.startsWith("upper_") || id.contains("roof") || id.contains("warmer")) {
            return StageZone.UPPER_TRUSS;
        }
        if (id.contains("floor") || id.contains("ground") || id.contains("rampe")) {
            return StageZone.FLOOR_PACKAGE;
        }
        if (id.contains("stage_") || id.contains("side") || id.contains("tower") || id.contains("dj_") || id.contains("booth")) {
            return StageZone.SIDE_TOWER;
        }
        if (id.contains("laser") || id.contains("fog") || id.contains("pyro") || id.contains("fx")) {
            return StageZone.SPECIAL_FX;
        }
        return StageZone.MAIN_TRUSS;
    }

    /**
     * Liefert den praxisnahen Verwendungszweck dieser Gerätegruppe im Lichtdesign.
     */
    public String usagePurpose() {
        return defaultUsagePurpose(this.groupId);
    }

    public static String defaultUsagePurpose(String groupId) {
        if (groupId == null) return "Bühnenbeleuchtung & Effekte";
        return switch (groupId.toLowerCase(Locale.ROOT)) {
            case "front_truss" -> "Frontale Künstlerausleuchtung, weiches Warm-/Kaltweiß-Washlight & Face-Spots";
            case "upper_front_truss" -> "Hohe Front-Traverse für steile Publikumsausleuchtung, Front-Blinder & Weitwinkel-Spots";
            case "upper_mid_truss" -> "Zentrale Hochtraverse für Luft-Fächer, Gobo-Projektionen auf die Bühne & Kegelwellen";
            case "upper_back_truss" -> "Oberste Hinter-Traverse für weit in den Raum strahlende Sharpies, Sky-Beams & Drop-Fächer";
            case "upper_roof_grid" -> "Dach-Raster / Decken-Matrix für senkrechte Downlights, Staccato-Rain & Chaser direkt von oben";
            case "mid_truss" -> "Mittlere Haupttraverse über der Bühne für Tiefenwirkung, Künstler-Center-Spots & Fächer";
            case "back_truss" -> "Hintere Haupt-Traverse für klassisches Gegenlicht, messerscharfe Beam-Wände & Blinder-Hits";
            case "stage_left" -> "Linker Bühnenturm / Gasse für plastisches Kreuzlicht, Musiker-Silhouetten & Seitenfächer";
            case "stage_right" -> "Rechter Bühnenturm / Gasse für plastisches Kreuzlicht, Musiker-Silhouetten & Seitenfächer";
            case "floor_beams" -> "Am Boden montierte Moving-Head Beams für dramatische Strahlenfächer steil nach oben (Uplights)";
            case "floor_blinders" -> "Rampe & Podestkanten für Sunstrip-Lauflicht, Nahfeld-Blinder & Bühnenbegrenzung";
            case "audience_blinders" -> "2-Lite & 4-Lite Fluter zum Einbinden des Publikums, Mitsing-Momente & massive Strobe-Schläge";
            case "dj_booth_center" -> "Zentrierte Spezialeffekte rund um DJ-Kanzel, Drum-Riser, Center-Halo-Ringe & Akzente";
            case "laser_rig" -> "Professionelle Show-Laser für 2D-Audience-Scanning-Fächer & punktgenaue 3D-Nadelstrahlen";
            case "fx_fog_pyro" -> "Dunst-Erzeugung für sichtbare Beams (Hazer), schwere Bodennebel-Wellen & vertikale Pyro-Flammen";
            case "truss_warmers" -> "Innenbeleuchtung der Traversen-Aluminiumrohre (Truss Toner) für spektakuläre Bühnenarchitektur";
            default -> "Vielseitige DMX-Gerätegruppe für individuelle Licht- und Show-Inszenierung";
        };
    }

    public static List<FixtureGroup> createDefaultGroups() {
        return List.of(
                // --- 1. Haupt-Traversen (Main Grid) ---
                new FixtureGroup(
                        "front_truss",
                        "Main Front-Truss (Keylight & Wash)",
                        Material.CYAN_STAINED_GLASS,
                        0x00D8FF,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, -25f, 28f, 0f, 195f, 0.85f, 1.0f,
                        MovementPattern.PAN_SWEEP, 0.9f, 0.55f, 0.5f
                ),
                new FixtureGroup(
                        "mid_truss",
                        "Mid Stage-Truss (Center & Texture)",
                        Material.BLUE_STAINED_GLASS,
                        0x2980B9,
                        ControlMode.MANUAL_LIVE,
                        0.90f, 0f, -20f, 22f, 0f, 220f, 0.90f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.0f, 0.55f, 0.6f
                ),
                new FixtureGroup(
                        "back_truss",
                        "Main Back-Truss (Backlight & Wall)",
                        Material.PURPLE_STAINED_GLASS,
                        0xB833FF,
                        ControlMode.MANUAL_LIVE,
                        0.95f, 0f, -12f, 14f, 0f, 285f, 0.90f, 1.0f,
                        MovementPattern.CIRCLE, 1.2f, 0.60f, 0.6f
                ),
                new FixtureGroup(
                        "audience_blinders",
                        "Audience Blinder Rig (Crowd Wash)",
                        Material.REDSTONE_LAMP,
                        0xFF3838,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, -15f, 60f, 0f, 40f, 0.20f, 1.0f,
                        MovementPattern.STATIC, 1.0f, 0.0f, 0.0f
                ),

                // --- 2. Obere Traversen (Upper Rigging & Roof) ---
                new FixtureGroup(
                        "upper_front_truss",
                        "Upper Front-Truss (High Key & Spots)",
                        Material.LIGHT_BLUE_STAINED_GLASS,
                        0x38B6FF,
                        ControlMode.MANUAL_LIVE,
                        0.95f, 0f, -35f, 32f, 0f, 205f, 0.80f, 1.0f,
                        MovementPattern.FAN_SPREAD, 1.0f, 0.65f, 0.6f
                ),
                new FixtureGroup(
                        "upper_mid_truss",
                        "Upper Mid-Truss (Aerial Beams & Gobos)",
                        Material.YELLOW_STAINED_GLASS,
                        0xF1C40F,
                        ControlMode.MANUAL_LIVE,
                        0.90f, 0f, -20f, 20f, 0f, 50f, 0.95f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.1f, 0.60f, 0.7f
                ),
                new FixtureGroup(
                        "upper_back_truss",
                        "Upper Back-Truss (Sky Beams & Silhouette)",
                        Material.MAGENTA_STAINED_GLASS,
                        0xE056FD,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, -15f, 12f, 0f, 310f, 0.95f, 1.0f,
                        MovementPattern.CIRCLE, 1.2f, 0.75f, 0.6f
                ),
                new FixtureGroup(
                        "upper_roof_grid",
                        "Upper Roof Grid (Overhead Matrix)",
                        Material.WHITE_STAINED_GLASS,
                        0xF0F4FF,
                        ControlMode.MANUAL_LIVE,
                        0.85f, 0f, -90f, 24f, 0f, 0f, 0.0f, 1.0f,
                        MovementPattern.FIGURE_8, 0.8f, 0.40f, 0.5f
                ),
                new FixtureGroup(
                        "truss_warmers",
                        "Truss Warmers (Traversenlicht)",
                        Material.GLOWSTONE,
                        0xFD79A8,
                        ControlMode.MANUAL_LIVE,
                        0.85f, 0f, 0f, 50f, 0f, 330f, 0.85f, 1.0f,
                        MovementPattern.STATIC, 1.0f, 0.0f, 0.0f
                ),

                // --- 3. Seitentürme & Kanzel (Side Towers & Center) ---
                new FixtureGroup(
                        "stage_left",
                        "Side Tower Links (Kreuzlicht & Side Beams)",
                        Material.LIME_STAINED_GLASS,
                        0x33FF66,
                        ControlMode.MANUAL_LIVE,
                        0.85f, 35f, -10f, 26f, 0f, 130f, 0.90f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.0f, 0.55f, 0.7f
                ),
                new FixtureGroup(
                        "stage_right",
                        "Side Tower Rechts (Kreuzlicht & Side Beams)",
                        Material.ORANGE_STAINED_GLASS,
                        0xFF8800,
                        ControlMode.MANUAL_LIVE,
                        0.85f, -35f, -10f, 26f, 0f, 30f, 0.90f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.0f, 0.55f, 0.7f
                ),
                new FixtureGroup(
                        "dj_booth_center",
                        "DJ Booth & Center Special (Halo & Kanzel)",
                        Material.SEA_LANTERN,
                        0x00F2FE,
                        ControlMode.MANUAL_LIVE,
                        0.90f, 0f, -45f, 30f, 0f, 180f, 0.85f, 1.0f,
                        MovementPattern.CIRCLE, 0.8f, 0.45f, 0.5f
                ),

                // --- 4. Boden-Set & Rampe (Ground Package) ---
                new FixtureGroup(
                        "floor_beams",
                        "Floor Beams (Steilstrahler & Riser Uplights)",
                        Material.GOLD_BLOCK,
                        0xF39C12,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, 65f, 8f, 0f, 45f, 0.95f, 1.0f,
                        MovementPattern.FAN_SPREAD, 1.3f, 0.50f, 0.8f
                ),
                new FixtureGroup(
                        "floor_blinders",
                        "Floor Blinder & Sunstrips (Bühnenkante)",
                        Material.OCHRE_FROGLIGHT,
                        0xFFA502,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, 15f, 45f, 0f, 35f, 0.60f, 1.0f,
                        MovementPattern.STATIC, 1.0f, 0.0f, 0.0f
                ),

                // --- 5. Show-Laser & Atmosphäre FX (Special FX) ---
                new FixtureGroup(
                        "laser_rig",
                        "Show-Laser System (Scanning Fans & Beams)",
                        Material.AMETHYST_CLUSTER,
                        0xFF007F,
                        ControlMode.MANUAL_LIVE,
                        0.92f, 0f, -5f, 46f, 0f, 320f, 0.98f, 1.0f,
                        MovementPattern.FIGURE_8, 1.5f, 0.70f, 0.4f
                ),
                new FixtureGroup(
                        "fx_fog_pyro",
                        "Atmosphere & FX (Hazer, Bodennebel & Pyro)",
                        Material.CAMPFIRE,
                        0xDCE8FF,
                        ControlMode.MANUAL_LIVE,
                        1.0f, 0f, 0f, 30f, 0f, 200f, 0.15f, 1.0f,
                        MovementPattern.STATIC, 1.0f, 0.0f, 0.0f
                )
        );
    }

    public String groupId() {
        return groupId;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    public Material iconMaterial() {
        return iconMaterial;
    }

    public int accentRgb() {
        return accentRgb;
    }

    public ControlMode controlMode() {
        return controlMode;
    }

    public void setControlMode(ControlMode controlMode) {
        if (controlMode != null) {
            this.controlMode = controlMode;
        }
    }

    public float dimmer() {
        return dimmer;
    }

    public void setDimmer(float dimmer) {
        this.dimmer = clampFinite(dimmer, 0f, 1f, this.dimmer);
    }

    public float panDegrees() {
        return panDegrees;
    }

    public void setPanDegrees(float panDegrees) {
        this.panDegrees = clampFinite(panDegrees, -180f, 180f, this.panDegrees);
    }

    public float tiltDegrees() {
        return tiltDegrees;
    }

    public void setTiltDegrees(float tiltDegrees) {
        this.tiltDegrees = clampFinite(tiltDegrees, -90f, 90f, this.tiltDegrees);
    }

    public float zoomDegrees() {
        return zoomDegrees;
    }

    public void setZoomDegrees(float zoomDegrees) {
        this.zoomDegrees = clampFinite(zoomDegrees, 4f, 70f, this.zoomDegrees);
    }

    public float strobeHz() {
        return strobeHz;
    }

    public void setStrobeHz(float strobeHz) {
        this.strobeHz = clampFinite(strobeHz, 0f, MAX_SAFE_STROBE_HZ, 0f);
    }

    public float hueDegrees() {
        return hueDegrees;
    }

    public void setHueDegrees(float hueDegrees) {
        this.hueDegrees = normalizeHue(hueDegrees);
    }

    public float saturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = clampFinite(saturation, 0f, 1f, this.saturation);
    }

    public float brightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = clampFinite(brightness, 0f, 1f, this.brightness);
    }

    public MovementPattern movementPattern() {
        return movementPattern;
    }

    public void setMovementPattern(MovementPattern movementPattern) {
        if (movementPattern != null) {
            this.movementPattern = movementPattern;
        }
    }

    public float movementSpeed() {
        return movementSpeed;
    }

    public void setMovementSpeed(float movementSpeed) {
        this.movementSpeed = clampFinite(movementSpeed, 0.1f, 4.0f, this.movementSpeed);
    }

    public float movementAmplitude() {
        return movementAmplitude;
    }

    public void setMovementAmplitude(float movementAmplitude) {
        this.movementAmplitude = clampFinite(movementAmplitude, 0f, 1f, this.movementAmplitude);
    }

    public float phaseSpread() {
        return phaseSpread;
    }

    public void setPhaseSpread(float phaseSpread) {
        this.phaseSpread = clampFinite(phaseSpread, 0f, 1f, this.phaseSpread);
    }

    public de.archer.admx.audio.SoundToLightConfig getSoundConfig() {
        if (soundConfig == null) {
            soundConfig = de.archer.admx.audio.SoundToLightConfig.defaultForGroup(groupId);
        }
        return soundConfig;
    }

    public void setSoundConfig(de.archer.admx.audio.SoundToLightConfig soundConfig) {
        this.soundConfig = (soundConfig != null) ? soundConfig : de.archer.admx.audio.SoundToLightConfig.defaultForGroup(groupId);
    }

    public Color toBukkitColor() {
        return Oklab.hsbToBukkitColor(hueDegrees, saturation, brightness);
    }

    public Oklab toOklabColor() {
        return Oklab.fromHSB(hueDegrees, saturation, brightness);
    }

    public SceneCue.GroupSnapshot createSnapshot() {
        return new SceneCue.GroupSnapshot(
                groupId,
                dimmer,
                panDegrees,
                tiltDegrees,
                zoomDegrees,
                strobeHz,
                hueDegrees,
                saturation,
                brightness,
                movementPattern,
                movementSpeed,
                movementAmplitude,
                phaseSpread
        );
    }

    /**
     * Übernimmt einen Snapshot mit strikter Bereichs-Validierung aller Werte (SEC-08).
     */
    public void applySnapshot(SceneCue.GroupSnapshot snap) {
        if (snap == null) return;
        setDimmer(snap.dimmer());
        setPanDegrees(snap.panDegrees());
        setTiltDegrees(snap.tiltDegrees());
        setZoomDegrees(snap.zoomDegrees());
        setStrobeHz(snap.strobeHz());
        setHueDegrees(snap.hueDegrees());
        setSaturation(snap.saturation());
        setBrightness(snap.brightness());
        setMovementPattern(snap.movementPattern() != null ? snap.movementPattern() : MovementPattern.STATIC);
        setMovementSpeed(snap.movementSpeed());
        setMovementAmplitude(snap.movementAmplitude());
        setPhaseSpread(snap.phaseSpread());
    }

    /**
     * Berechnet den Echtzeit-DMX-Zustand ({@link FixtureState}) für ein spezifisches Gerät innerhalb
     * dieser Gruppe unter Berücksichtigung seines eindeutigen Gruppen-Index (memberIndex).
     */
    public FixtureState computeMemberState(
            FixtureType type,
            int memberIndex,
            int totalMembers,
            long tickCounter
    ) {
        if (controlMode == ControlMode.BLACKOUT || dimmer <= 0.005f) {
            return new FixtureState(panDegrees, tiltDegrees, 0f, toOklabColor(), zoomDegrees, 0f);
        }

        float normalizedPos = (totalMembers > 1)
                ? (memberIndex / (float) (totalMembers - 1)) - 0.5f
                : 0f;
        double memberPhase = (totalMembers > 1)
                ? (memberIndex * 2.0 * Math.PI * phaseSpread / totalMembers)
                : 0.0;

        // Berechne Phase in double mit Modulo 2*PI, damit nach langer Server-Uptime kein Float-Stottern auftritt (BUG-02)
        double twoPi = 2.0 * Math.PI;
        double t = ((tickCounter * 0.05 * movementSpeed) + memberPhase) % twoPi;

        float calcPan = panDegrees;
        float calcTilt = tiltDegrees;

        switch (movementPattern) {
            case STATIC -> {
                calcPan = panDegrees + normalizedPos * 45f * phaseSpread;
                calcTilt = tiltDegrees;
            }
            case CIRCLE -> {
                calcPan = panDegrees + (float) Math.sin(t) * (95f * movementAmplitude);
                calcTilt = tiltDegrees + (float) Math.cos(t) * (32f * movementAmplitude);
            }
            case PAN_SWEEP -> {
                calcPan = panDegrees + (float) Math.sin(t) * (115f * movementAmplitude);
                calcTilt = tiltDegrees;
            }
            case TILT_WAVE -> {
                calcPan = panDegrees + normalizedPos * 40f * phaseSpread;
                calcTilt = tiltDegrees + (float) Math.sin(t * 1.3) * (35f * movementAmplitude);
            }
            case FIGURE_8 -> {
                calcPan = panDegrees + (float) Math.sin(t) * (105f * movementAmplitude);
                calcTilt = tiltDegrees + (float) Math.sin(t * 2.0) * (30f * movementAmplitude);
            }
            case FAN_SPREAD -> {
                float fanAngle = normalizedPos * (110f * Math.max(0.25f, movementAmplitude));
                calcPan = panDegrees + fanAngle;
                calcTilt = tiltDegrees + (float) Math.cos(t) * (12f * movementAmplitude);
            }
            case HARD_SNAP_STACCATO -> {
                // Hard-Snap Staccato: Springt exakt im Takt auf diskrete Haltepositionen und friert dort starr ein
                // 8 markante Positionen (Cross, Fan, Punch, Sky, Center), ideal für 146 BPM Kicks
                int step = (int) Math.floor((t / twoPi) * 8.0) % 8;
                float[] snapPan = {-65f, 0f, 65f, 0f, -50f, 50f, -85f, 85f};
                float[] snapTilt = {-25f, 45f, -25f, 60f, -15f, -15f, 35f, 35f};
                int idx = Math.abs((step + memberIndex) % snapPan.length);
                calcPan = panDegrees + (snapPan[idx] * movementAmplitude) + (normalizedPos * 35f * phaseSpread);
                calcTilt = tiltDegrees + (snapTilt[idx] * movementAmplitude);
            }
            case CLIFFHANGER_FREEZE -> {
                // Cliffhanger Freeze: Fixture friert sofort starr in einer dramatischen Spannungs-Pose ein
                calcPan = panDegrees + (normalizedPos * 30f * phaseSpread);
                calcTilt = 45f * movementAmplitude;
            }
            case CINEMATIC_STOMP_SNAP -> {
                // Cinematic Stomp Snap: Schlägt auf schwere Orchester-/Tom-Schläge hart ein
                int step = (int) Math.floor((t / twoPi) * 4.0) % 6;
                float[] stompPan = {-75f, 75f, 0f, -90f, 90f, 0f};
                float[] stompTilt = {45f, 45f, -30f, 60f, 60f, 20f};
                int idx = Math.abs((step + memberIndex) % stompPan.length);
                calcPan = panDegrees + (stompPan[idx] * movementAmplitude) + (normalizedPos * 25f * phaseSpread);
                calcTilt = tiltDegrees + (stompTilt[idx] * movementAmplitude);
            }
            case GENTLE_FAN_SWAY -> {
                // Sanfter Fächer-Sway: Breiter Fächer mit beruhigender horizontaler & vertikaler Schwingung
                float fanAngle = normalizedPos * (80f * phaseSpread);
                calcPan = panDegrees + fanAngle + (float) Math.sin(t * 0.7) * (25f * movementAmplitude);
                calcTilt = tiltDegrees + (float) Math.cos(t * 0.7) * (12f * movementAmplitude);
            }
            case BREATHING_CENTER_GLOW -> {
                // Atmender Center-Glow: Alle Köpfe zielen zentral und heben/senken sich langsam synchron
                calcPan = panDegrees + (normalizedPos * 18f * phaseSpread);
                calcTilt = tiltDegrees + (float) Math.sin(t * 0.5) * (22f * movementAmplitude);
            }
            case FLOOR_PUNCH_BREATHE -> {
                // Floor-Punch Breathe: Nach unten gerichteter Tanzflächen-Punch mit rhythmischem Atem-Effekt
                calcPan = panDegrees + (float) Math.sin(t * 1.2) * (30f * movementAmplitude);
                calcTilt = -40f + (float) Math.cos(t * 1.2) * (18f * movementAmplitude);
            }
            case NEEDLE_RACE_STACCATO -> {
                // Needle Race: Schnelle staccatoartige Sprünge über 8 diskrete Haltepositionen im Raum
                int step = (int) Math.floor((t / twoPi) * 8.0) % 8;
                float[] racePan = {-70f, -30f, 20f, 65f, 40f, -10f, -50f, 0f};
                float[] raceTilt = {20f, -15f, 40f, 10f, -20f, 35f, -10f, 50f};
                int idx = Math.abs((step + memberIndex) % racePan.length);
                calcPan = panDegrees + (racePan[idx] * movementAmplitude) + (normalizedPos * 30f * phaseSpread);
                calcTilt = tiltDegrees + (raceTilt[idx] * movementAmplitude);
            }
            case WIDE_ORBITAL_SUNSET -> {
                // Weites Sunset-Orbital: Sanft kreisende, raumfüllende Lissajous-Achterbahn
                calcPan = panDegrees + (float) Math.sin(t * 0.75) * (110f * movementAmplitude);
                calcTilt = tiltDegrees + (float) Math.sin(t * 1.5) * (35f * movementAmplitude);
            }
            case CANDLE_FLICKER_STATIC -> {
                // Kerzenflackern: Statische Ausrichtung mit organischem Mikrozittern
                float microJitterPan = (float) (Math.sin(t * 4.3 + memberIndex) * 3.5 * movementAmplitude);
                float microJitterTilt = (float) (Math.cos(t * 3.7 + memberIndex) * 2.5 * movementAmplitude);
                calcPan = panDegrees + (normalizedPos * 35f * phaseSpread) + microJitterPan;
                calcTilt = tiltDegrees + microJitterTilt;
            }
        }

        // Strobe-Berechnung (nicht auf Nebelmaschinen anwenden, um Cache-Resets zu verhindern -> PERF-03)
        float effectiveDimmer = dimmer;
        if (!type.isFogMachine() && strobeHz > 0.2f) {
            int periodTicks = Math.max(2, Math.round(20f / strobeHz));
            boolean flashOn = ((tickCounter + memberIndex) % periodTicks) < (periodTicks / 2);
            if (!flashOn) {
                effectiveDimmer = 0f;
            }
        }

        float effectiveZoom = switch (type) {
            case MOVING_HEAD_BEAM, LASER_BEAM_PROJECTOR -> Math.clamp(zoomDegrees * 0.2f, 2.5f, 8.0f);
            case MOVING_HEAD_WASH, BLINDER_2LITE, STROBE_BAR -> Math.clamp(zoomDegrees * 1.4f, 28f, 68f);
            default -> Math.clamp(zoomDegrees, 6f, 62f);
        };

        Oklab color = type.isFogMachine() ? type.fogType().baseColor() : toOklabColor();
        return new FixtureState(calcPan, calcTilt, effectiveDimmer, color, effectiveZoom, strobeHz);
    }
}
