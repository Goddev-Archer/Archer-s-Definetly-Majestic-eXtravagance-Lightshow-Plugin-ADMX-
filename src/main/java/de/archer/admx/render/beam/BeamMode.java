package de.archer.admx.render.beam;

/**
 * Strahl-Rendering-Modus für Fixtures.
 */
public enum BeamMode {
    /**
     * Volumetrischer Laser-Fächer aus gescherten TextDisplays (hohe optische Dichte, 0 Partikel).
     */
    LASER_FAN,

    /**
     * Messerscharfer Laser-Nadelstrahl (dünnes TextDisplay-Quad).
     */
    LASER_NEEDLE,

    /**
     * Scheinwerfer-Kegel für Moving Heads / Spots (Kombination aus Fächer/Polygon und Auftreff-Glow).
     */
    SPOT_CONE
}
