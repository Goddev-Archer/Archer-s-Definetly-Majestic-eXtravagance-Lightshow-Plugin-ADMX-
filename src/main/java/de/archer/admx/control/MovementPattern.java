package de.archer.admx.control;

/**
 * Programmierbare Bewegungs-Effekte (Shape Generator) für eine Gerätegruppe.
 * Berücksichtigt den eindeutigen Phasen-Index jedes Geräts innerhalb der Gruppe,
 * um echte Bühnen-Chases (Wellen, Fächer, Kreise) zu erzeugen.
 */
public enum MovementPattern {
    /**
     * Statische Ausrichtung exakt auf die eingestellten Pan-/Tilt-Werte.
     */
    STATIC("Statisch (Fest)"),

    /**
     * Gleichmäßige Kreisbewegung um den eingestellten Pan-/Tilt-Mittelpunkt.
     */
    CIRCLE("Kreis-Rotation"),

    /**
     * Horizontaler Pan-Sweep über die Bühne.
     */
    PAN_SWEEP("Pan-Sweep"),

    /**
     * Vertikale Tilt-Welle (ideal mit Phasen-Fanning über mehrere Geräte).
     */
    TILT_WAVE("Tilt-Welle"),

    /**
     * Dynamische liegende Acht (Lissajous Figure-8).
     */
    FIGURE_8("Figure-8"),

    /**
     * Symmetrischer Fächer (Geräte fächern sich anhand ihrer eindeutigen Gruppen-Position auf).
     */
    FAN_SPREAD("Symmetrischer Fächer"),

    /**
     * Staccato-Sprünge (Hard Snap): Köpfe springen schlagartig auf diskrete Winkel und verharren
     * starr bis zum nächsten Beat. Speziell für Hardcore-/Schranz-Tracks (z. B. 146 BPM).
     */
    HARD_SNAP_STACCATO("Hard Snap (Staccato)"),

    /**
     * Cliffhanger Freeze: Kick bricht schlagartig ab. Köpfe frieren starr in dramatischer
     * Spannungs-Pose ein, Dimmer fällt ab – maximale Spannung vor dem Drop!
     */
    CLIFFHANGER_FREEZE("Cliffhanger Freeze (Starr)"),

    /**
     * Cinematic Stomp Snap: Schlägt synchron auf schwere Orchester-/Tom-Schläge ein.
     * Sofortiger 100% Snap mit natürlichem Nachhall-Fadeout.
     */
    CINEMATIC_STOMP_SNAP("Cinematic Stomp Snap"),

    /**
     * Sanfter Fächer-Sway: Breiter, beruhigender Fächer mit langsamer horizontaler und vertikaler Schwingung.
     */
    GENTLE_FAN_SWAY("Sanfter Fächer-Sway"),

    /**
     * Atmender Center-Glow: Alle Köpfe konvergieren zur Bühnenmitte mit synchroner Atembewegung.
     */
    BREATHING_CENTER_GLOW("Atmender Center-Glow"),

    /**
     * Floor-Punch Breathe: Nach unten gerichteter Tanzflächen-Fokus mit kraftvollem Bass-Pumpen.
     */
    FLOOR_PUNCH_BREATHE("Floor-Punch Breathe"),

    /**
     * Needle Race (Staccato): Messerscharfe Beambewegungen springen staccatoartig über Raumkoordinaten.
     */
    NEEDLE_RACE_STACCATO("Needle Race (Staccato)"),

    /**
     * Weites Sunset-Orbital: Raumfüllende, weite Lissajous-Kurve für atmosphärische Höhepunkte.
     */
    WIDE_ORBITAL_SUNSET("Weites Sunset-Orbital"),

    /**
     * Kerzenflackern (Atmosphärisch): Statisch fokussierte Beams mit organischem Mikrozittern.
     */
    CANDLE_FLICKER_STATIC("Kerzenflackern (Atmosphärisch)");

    private final String displayName;

    MovementPattern(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public MovementPattern next() {
        MovementPattern[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
