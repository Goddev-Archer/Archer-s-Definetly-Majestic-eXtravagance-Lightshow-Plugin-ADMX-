package de.archer.admx.audio;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Durch den Beat- und Onset-Klassifikator automatisch erkannte Musik-Genres.
 */
public enum MusicGenre {
    TECHNO(
            "Techno / EDM",
            128.0, 155.0,
            NamedTextColor.DARK_AQUA,
            "Schnelle, starre 4-on-the-Floor Kicks, minimale Intervall-Varianz, industrielle Kaltweiß-/Acid-Lichtästhetik."
    ),
    RAP_HIPHOP(
            "Rap / Trap / Hip-Hop",
            65.0, 95.0,
            NamedTextColor.GOLD,
            "Synkopierte Offbeat-Kicks, lang ausklingender 808-Sub-Bass, langsame Pan-Sweeps mit vertikalem Nodding, weiche Dimmer-Resonanz."
    ),
    DRUM_AND_BASS(
            "Drum & Bass",
            160.0, 180.0,
            NamedTextColor.RED,
            "Sehr schnelles Tempo, synkopierte Breakbeat-Kicks, messerscharfe Beam-Wechsel, schnelle Laser-Fächer."
    ),
    HOUSE_CLUB(
            "House / Club",
            120.0, 128.0,
            NamedTextColor.LIGHT_PURPLE,
            "Gleichmäßiger 4/4-Groove, warme Farbverläufe, federnde Kreis- und Sweep-Bewegungen."
    ),
    AMBIENT(
            "Ambient / Intro",
            50.0, 120.0,
            NamedTextColor.GRAY,
            "Wenig bis keine Kicks, atmosphärische sanfte Lichtteppiche und langsame Farb-Fades."
    ),
    ACOUSTIC_TRAP(
            "Acoustic / Hybrid Trap",
            70.0, 115.0,
            NamedTextColor.GOLD,
            "Akustische Melodielinien kombiniert mit tiefen Trap-Bässen, atmosphärische weite Fächer und sanfte Atem-Glows."
    ),
    MELODIC_EDM(
            "Melodic EDM / Future Bass",
            125.0, 155.0,
            NamedTextColor.AQUA,
            "Melodische Akkordfolgen, euphorische Hooklines, raumfüllende Orbital- und Sweep-Chases."
    );

    private final String displayName;
    private final double minBpm;
    private final double maxBpm;
    private final NamedTextColor color;
    private final String description;

    MusicGenre(String displayName, double minBpm, double maxBpm, NamedTextColor color, String description) {
        this.displayName = displayName;
        this.minBpm = minBpm;
        this.maxBpm = maxBpm;
        this.color = color;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public double minBpm() {
        return minBpm;
    }

    public double maxBpm() {
        return maxBpm;
    }

    public NamedTextColor color() {
        return color;
    }

    public String description() {
        return description;
    }

    public Component titleComponent() {
        return Component.text(displayName, color);
    }
}
