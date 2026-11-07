package de.archer.admx.flow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Verfügbare Node-Typen für das visuelle Flowboard-Programmiersystem.
 */
public enum FlowNodeType {
    COLOR_FADE(
            "Color Crossfade",
            Material.AMETHYST_SHARD,
            NamedTextColor.LIGHT_PURPLE,
            "Weicher Farb-Überblendungs-Effekt zu einem Zielfarbton über die Node-Dauer."
    ),
    COLOR_RAINBOW(
            "Rainbow Chase",
            Material.PRISMARINE_CRYSTALS,
            NamedTextColor.AQUA,
            "Kontinuierlicher Regenbogen-Farbverlauf über alle Geräte der Gruppe."
    ),
    MOVEMENT_SHAPE(
            "Movement Shape",
            Material.ELYTRA,
            NamedTextColor.GOLD,
            "Aktiviert Pan/Tilt-Bewegungsformen (Kreis, Sweep, Welle, Figure-8) mit Amplitude & Speed."
    ),
    DIMMER_PULSE(
            "Dimmer Pulse",
            Material.REDSTONE_LAMP,
            NamedTextColor.YELLOW,
            "Sanft an- und abschwellender Helligkeits-Impuls (Sinus-Kurve)."
    ),
    STROBE_BLAST(
            "Strobe Blast",
            Material.LIGHTNING_ROD,
            NamedTextColor.RED,
            "Intensiver Strobe-Blitz-Effekt (strikt fotosensibilitäts-gedeckelt auf max. 10 Hz)."
    ),
    FOG_BURST(
            "Fog Burst",
            Material.COBWEB,
            NamedTextColor.WHITE,
            "Stößt einen dichten Nebelschwall aus und erzeugt eine flüssige Wellenfront."
    ),
    FLAME_SHOT(
            "Flame Shot",
            Material.BLAZE_POWDER,
            NamedTextColor.GOLD,
            "Löst eine synchrone Bühnenflammen-Säule aus."
    ),
    BLACKOUT_DROP(
            "Blackout Drop",
            Material.OBSIDIAN,
            NamedTextColor.DARK_GRAY,
            "Schaltet alle Geräte schlagartig auf Dunkelheit für dramatische Drops."
    );

    private final String displayName;
    private final Material icon;
    private final NamedTextColor color;
    private final String description;

    FlowNodeType(String displayName, Material icon, NamedTextColor color, String description) {
        this.displayName = displayName;
        this.icon = icon;
        this.color = color;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
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
