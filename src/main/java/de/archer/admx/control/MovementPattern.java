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
    FAN_SPREAD("Symmetrischer Fächer");

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
