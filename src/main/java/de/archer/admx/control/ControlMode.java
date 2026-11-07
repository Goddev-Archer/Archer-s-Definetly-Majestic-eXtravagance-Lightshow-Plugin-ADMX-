package de.archer.admx.control;

/**
 * Betriebsmodus einer Gerätegruppe im ADMX-Steuerpult.
 */
public enum ControlMode {
    /**
     * Manuelle Live-Steuerung über das ausgewählte UI (Chest-UI oder 3D-Holo-UI).
     */
    MANUAL_LIVE("Live-Pult"),

    /**
     * Automatischer Demo-/Musik-Show-Modus.
     */
    AUTO_SHOW("Auto-Show"),

    /**
     * Hardware-Style Sound-to-Light Modus basierend auf Frequenzbändern und Schwellenwerten (Thresholds).
     */
    SOUND_ACTIVE("Sound-Active (S2L)"),

    /**
     * Gesteuert durch die programmierte Szenen-Timeline (Cue-Sequencer).
     */
    SCENE_PLAYBACK("Szenen-Sequencer"),

    /**
     * Sofortiges Dunkelschalten aller Geräte in dieser Gruppe (Dimmer = 0).
     */
    BLACKOUT("Blackout");

    private final String displayName;

    ControlMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public ControlMode next() {
        ControlMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
