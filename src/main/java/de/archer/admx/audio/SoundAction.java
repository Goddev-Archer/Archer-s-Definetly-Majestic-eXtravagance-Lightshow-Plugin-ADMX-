package de.archer.admx.audio;

/**
 * Aktionen, die im Hardware-Style Sound-to-Light Modus ausgelöst werden,
 * wenn der Frequenzpegel einen Schwellenwert (Threshold) über- oder unterschreitet.
 */
public enum SoundAction {
    /**
     * Schaltet die Gruppe vollständig aus (Dimmer = 0 %, Strobe = 0 Hz).
     */
    BLACKOUT("Aus / Blackout", "Schaltet die Gruppe komplett dunkel"),

    /**
     * Sanfte, gedimmte Grundbeleuchtung (z. B. 20 % Dimmer) mit langsamer Pan-Fahrt.
     */
    DIMMED_SWEEP("Gedimmter Sweep (20 %)", "Ruhige, gedimmte Fahrt im Ruhezustand"),

    /**
     * Statische Grundfarbe bei mittlerer Helligkeit (45 % Dimmer).
     */
    STATIC_COLOR("Statische Grundfarbe (45 %)", "Konstante Raumbeleuchtung ohne Bewegung"),

    /**
     * Sicherer 10 Hz Stroboskop-Blitz (strikt gedeckelt gem. SEC-09).
     */
    STROBE_10HZ("10 Hz Stroboskop-Blitz", "Schnelle Blitz-Salven auf Beat-Einschlägen"),

    /**
     * Voller weißer Blinder-Flash (100 % Dimmer, weißer Lichtimpuls mit kurzem Decay).
     */
    BLINDER_HIT("Blinder Full-Flash", "Massiver weißer Lichtschlag auf Bass-Kicks"),

    /**
     * Springt bei jedem Trigger-Impuls zur nächsten Farbe in der Palette.
     */
    COLOR_CYCLE("Farb-Wechsel (Sprung)", "Wechselt bei jedem Peak zur nächsten Palette-Farbe"),

    /**
     * Schrittweises Weiterschalten der aktiven Geräte in der Gruppe (Lauflicht / Chase).
     */
    CHASE_STEP("Lauflicht-Schritt (Chase)", "Schaltet Gerät für Gerät der Reihe nach durch"),

    /**
     * Reißt Laser-Fächer auf maximale Breite auf und zündet Strahlen.
     */
    LASER_FAN_BURST("Laser Fächer-Burst", "Öffnet Fächerwinkel und zerhackt Laserstrahlen");

    private final String displayName;
    private final String description;

    SoundAction(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public SoundAction next() {
        SoundAction[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }

    public SoundAction prev() {
        SoundAction[] vals = values();
        return vals[(ordinal() - 1 + vals.length) % vals.length];
    }
}
