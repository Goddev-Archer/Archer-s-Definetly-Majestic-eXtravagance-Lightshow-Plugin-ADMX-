package de.archer.admx.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Konfiguration für das Hardware-Style Sound-to-Light System einer Gerätegruppe.
 * Ermöglicht es, jeder Gruppe einen individuellen Frequenzbereich (bis 4000 Hz),
 * einen Threshold-Schwellenwert sowie Aktionen vor und nach Überschreiten des Thresholds zuzuweisen.
 */
public final class SoundToLightConfig {

    public static final int MAX_FREQ_HZ = 4000;
    public static final int MIN_FREQ_HZ = 30;

    private boolean enabled;
    private int lowHz;
    private int highHz;
    private float threshold; // 0.05 .. 0.95
    private SoundAction belowAction;
    private SoundAction aboveAction;
    private int holdMs; // Dauer des Trigger-Zustands in Millisekunden

    public SoundToLightConfig(
            boolean enabled,
            int lowHz,
            int highHz,
            float threshold,
            SoundAction belowAction,
            SoundAction aboveAction,
            int holdMs
    ) {
        this.enabled = enabled;
        this.lowHz = Math.clamp(lowHz, MIN_FREQ_HZ, MAX_FREQ_HZ - 20);
        this.highHz = Math.clamp(highHz, this.lowHz + 20, MAX_FREQ_HZ);
        this.threshold = (float) Math.clamp(threshold, 0.05, 0.95);
        this.belowAction = (belowAction != null) ? belowAction : SoundAction.BLACKOUT;
        this.aboveAction = (aboveAction != null) ? aboveAction : SoundAction.STROBE_10HZ;
        this.holdMs = Math.clamp(holdMs, 40, 500);
    }

    public SoundToLightConfig() {
        this(true, 35, 140, 0.45f, SoundAction.BLACKOUT, SoundAction.STROBE_10HZ, 120);
    }

    public static SoundToLightConfig defaultForGroup(String groupId) {
        String lower = groupId.toLowerCase();
        if (lower.contains("strobe") || lower.contains("blinder")) {
            return new SoundToLightConfig(true, 35, 120, 0.42f, SoundAction.BLACKOUT, SoundAction.STROBE_10HZ, 100);
        } else if (lower.contains("laser")) {
            return new SoundToLightConfig(true, 120, 900, 0.40f, SoundAction.DIMMED_SWEEP, SoundAction.LASER_FAN_BURST, 150);
        } else if (lower.contains("flame")) {
            return new SoundToLightConfig(true, 35, 90, 0.55f, SoundAction.BLACKOUT, SoundAction.BLINDER_HIT, 80);
        } else {
            return new SoundToLightConfig(true, 40, 250, 0.40f, SoundAction.DIMMED_SWEEP, SoundAction.COLOR_CYCLE, 180);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int lowHz() {
        return lowHz;
    }

    public void setLowHz(int lowHz) {
        this.lowHz = Math.clamp(lowHz, MIN_FREQ_HZ, highHz - 20);
    }

    public int highHz() {
        return highHz;
    }

    public void setHighHz(int highHz) {
        this.highHz = Math.clamp(highHz, lowHz + 20, MAX_FREQ_HZ);
    }

    public float threshold() {
        return threshold;
    }

    public void setThreshold(float threshold) {
        this.threshold = (float) Math.clamp(threshold, 0.05, 0.95);
    }

    public SoundAction belowAction() {
        return belowAction;
    }

    public void setBelowAction(SoundAction belowAction) {
        if (belowAction != null) {
            this.belowAction = belowAction;
        }
    }

    public SoundAction aboveAction() {
        return aboveAction;
    }

    public void setAboveAction(SoundAction aboveAction) {
        if (aboveAction != null) {
            this.aboveAction = aboveAction;
        }
    }

    public int holdMs() {
        return holdMs;
    }

    public void setHoldMs(int holdMs) {
        this.holdMs = Math.clamp(holdMs, 40, 500);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("lowHz", lowHz);
        obj.addProperty("highHz", highHz);
        obj.addProperty("threshold", threshold);
        obj.addProperty("belowAction", belowAction.name());
        obj.addProperty("aboveAction", aboveAction.name());
        obj.addProperty("holdMs", holdMs);
        return obj;
    }

    public static SoundToLightConfig fromJsonString(String jsonString) {
        return fromJson(jsonString);
    }

    public static SoundToLightConfig fromJson(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return new SoundToLightConfig();
        }
        try {
            JsonObject obj = JsonParser.parseString(jsonString).getAsJsonObject();
            boolean enabled = obj.has("enabled") ? obj.get("enabled").getAsBoolean() : true;
            int low = obj.has("lowHz") ? obj.get("lowHz").getAsInt() : 35;
            int high = obj.has("highHz") ? obj.get("highHz").getAsInt() : 140;
            float thresh = obj.has("threshold") ? obj.get("threshold").getAsFloat() : 0.45f;
            SoundAction below = SoundAction.BLACKOUT;
            if (obj.has("belowAction")) {
                try { below = SoundAction.valueOf(obj.get("belowAction").getAsString()); } catch (Exception ignored) {}
            }
            SoundAction above = SoundAction.STROBE_10HZ;
            if (obj.has("aboveAction")) {
                try { above = SoundAction.valueOf(obj.get("aboveAction").getAsString()); } catch (Exception ignored) {}
            }
            int hold = obj.has("holdMs") ? obj.get("holdMs").getAsInt() : 120;
            return new SoundToLightConfig(enabled, low, high, thresh, below, above, hold);
        } catch (Exception e) {
            return new SoundToLightConfig();
        }
    }
}
