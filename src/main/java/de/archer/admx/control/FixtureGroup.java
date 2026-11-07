package de.archer.admx.control;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.List;

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

    public static List<FixtureGroup> createDefaultGroups() {
        return List.of(
                new FixtureGroup(
                        "front_truss",
                        "Gruppe 1: Front-Truss",
                        Material.CYAN_STAINED_GLASS,
                        0x00D8FF,
                        ControlMode.MANUAL_LIVE,
                        0.90f, 0f, -15f, 24f, 0f, 195f, 0.85f, 1.0f,
                        MovementPattern.PAN_SWEEP, 1.0f, 0.65f, 0.5f
                ),
                new FixtureGroup(
                        "back_truss",
                        "Gruppe 2: Back-Truss",
                        Material.PURPLE_STAINED_GLASS,
                        0xB833FF,
                        ControlMode.MANUAL_LIVE,
                        0.95f, 0f, -12f, 14f, 0f, 285f, 0.90f, 1.0f,
                        MovementPattern.CIRCLE, 1.2f, 0.60f, 0.6f
                ),
                new FixtureGroup(
                        "stage_left",
                        "Gruppe 3: Stage Left",
                        Material.LIME_STAINED_GLASS,
                        0x33FF66,
                        ControlMode.MANUAL_LIVE,
                        0.85f, 35f, -10f, 28f, 0f, 130f, 0.90f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.0f, 0.55f, 0.7f
                ),
                new FixtureGroup(
                        "stage_right",
                        "Gruppe 4: Stage Right",
                        Material.ORANGE_STAINED_GLASS,
                        0xFF8800,
                        ControlMode.MANUAL_LIVE,
                        0.85f, -35f, -10f, 28f, 0f, 30f, 0.90f, 1.0f,
                        MovementPattern.TILT_WAVE, 1.0f, 0.55f, 0.7f
                ),
                new FixtureGroup(
                        "laser_rig",
                        "Gruppe 5: Laser & Strobe",
                        Material.MAGENTA_STAINED_GLASS,
                        0xFF22AA,
                        ControlMode.MANUAL_LIVE,
                        0.92f, 0f, -6f, 46f, 0f, 320f, 0.98f, 1.0f,
                        MovementPattern.FIGURE_8, 1.5f, 0.70f, 0.4f
                ),
                new FixtureGroup(
                        "fx_fog_pyro",
                        "Gruppe 6: Nebel & Pyro FX",
                        Material.WHITE_STAINED_GLASS,
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
