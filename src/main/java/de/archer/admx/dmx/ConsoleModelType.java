package de.archer.admx.dmx;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * Definiert die platzierbaren 3D-DMX-Steuerpult-Modelle in ADMX.
 * Jedes Modell besitzt ein eigenes Blockbench-ItemModel im Resource-Pack,
 * eine eindeutige CustomModelData-ID sowie einen Vanilla-Block-Fallback.
 */
public enum ConsoleModelType {

    GRAND_CONSOLE(
            "ADMX Grand Console",
            "Großes Dual-Screen Touring-Lichtpult mit Motorfadern, Encoder-Wheels & Schwanenhalslampen",
            NamespacedKey.fromString("admx:dmx_console"),
            10015,
            Material.LECTERN,
            Material.RECOVERY_COMPASS,
            16
    ),

    STAIRVILLE_DDC6(
            "Stairville DDC-6 Controller",
            "Kompaktes 6-Kanal Taschen-DMX-Pult mit 6 Kanal-Fadern und Master-Regler",
            NamespacedKey.fromString("admx:stairville_ddc6"),
            10016,
            Material.DAYLIGHT_DETECTOR,
            Material.REPEATER,
            6
    ),

    STAIRVILLE_DMX_MASTER(
            "Stairville DMX-Master I",
            "Klassischer 19-Zoll 12-Scanner DMX-Rack-Controller mit Pan/Tilt Jog-Wheels",
            NamespacedKey.fromString("admx:stairville_dmx_master"),
            10017,
            Material.NOTE_BLOCK,
            Material.COMPARATOR,
            8
    ),

    BEHRINGER_WING(
            "Behringer WING Console",
            "Digitales Show- & Audio-Steuerpult mit kapazitivem Hauptdisplay & 3 Fader-Sektionen",
            NamespacedKey.fromString("admx:behringer_wing"),
            10018,
            Material.SMITHING_TABLE,
            Material.JUKEBOX,
            8
    ),

    YAMAHA_DM7(
            "Yamaha DM7 Digital Desk",
            "Kompakte High-End Digital-Produktionskonsole mit Dual-Touchscreen-Brücke",
            NamespacedKey.fromString("admx:yamaha_dm7"),
            10019,
            Material.CARTOGRAPHY_TABLE,
            Material.OBSERVER,
            8
    );

    private final String displayName;
    private final String description;
    private final NamespacedKey modelKey;
    private final int customModelData;
    private final Material vanillaFallbackBlock;
    private final Material guiIcon;
    private final int fadersPerBank;

    ConsoleModelType(
            String displayName,
            String description,
            NamespacedKey modelKey,
            int customModelData,
            Material vanillaFallbackBlock,
            Material guiIcon,
            int fadersPerBank
    ) {
        this.displayName = displayName;
        this.description = description;
        this.modelKey = modelKey;
        this.customModelData = customModelData;
        this.vanillaFallbackBlock = vanillaFallbackBlock;
        this.guiIcon = guiIcon;
        this.fadersPerBank = fadersPerBank;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String subtitle() {
        return description;
    }

    public NamespacedKey modelKey() {
        return modelKey;
    }

    public int customModelData() {
        return customModelData;
    }

    public Material vanillaFallbackBlock() {
        return vanillaFallbackBlock;
    }

    public Material guiIcon() {
        return guiIcon;
    }

    public Material iconMaterial() {
        return guiIcon;
    }

    public int fadersPerBank() {
        return fadersPerBank;
    }
}
