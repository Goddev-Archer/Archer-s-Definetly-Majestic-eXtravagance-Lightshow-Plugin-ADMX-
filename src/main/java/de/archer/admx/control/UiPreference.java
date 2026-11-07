package de.archer.admx.control;

/**
 * Auswahl des bevorzugten Steuerpult-UIs pro Spieler.
 */
public enum UiPreference {
    /**
     * Großes 54-Slot Doppelkisten-Steuerpult (Chest-UI).
     */
    CHEST_UI("Großes Chest-UI (54 Slots)"),

    /**
     * Interaktives 3D-Hologramm-Steuerpult in der Welt (inspiriert von minecraft-text-display-experiments),
     * das direkt über den ADMX-Wand an einer frei wählbaren Position gespawnt wird.
     */
    HOLO_UI("3D Hologramm-UI (via Wand platzierbar)");

    private final String displayName;

    UiPreference(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public UiPreference toggle() {
        return this == CHEST_UI ? HOLO_UI : CHEST_UI;
    }
}
