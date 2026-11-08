package de.archer.admx.dmx;

/**
 * Bestimmt, wie ein 512-Kanal DMX-Universum die drei Signalquellen
 * (1. Interner ADMX-Generator, 2. Platzierte In-Game DMX-Pulte, 3. Eingehender Art-Net 4 Stream)
 * zusammenführt.
 */
public enum DmxMergeMode {

    HTP(
            "HTP (Highest Takes Precedence)",
            "Der höchste Kanalwert (0..255) aus ADMX-Generator, In-Game-Pult und Art-Net Input gewinnt."
    ),

    LTP(
            "LTP (Latest Takes Precedence)",
            "Die zuletzt geänderte Quelle (Art-Net Stream, Pult-Fader oder Generator) steuert die Kanäle."
    ),

    CONSOLE_EXCLUSIVE(
            "In-Game DMX-Pult Exklusiv",
            "Nur die auf dieses Universum verlinkten 3D-DMX-Pulte in Minecraft steuern die 512 Kanäle."
    ),

    ARTNET_EXCLUSIVE(
            "Art-Net Input Exklusiv",
            "Externe DMX-Software (QLC+, grandMA, Chamsys) steuert dieses Universum exklusiv über Art-Net."
    );

    private final String displayName;
    private final String description;

    DmxMergeMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public DmxMergeMode next() {
        DmxMergeMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
