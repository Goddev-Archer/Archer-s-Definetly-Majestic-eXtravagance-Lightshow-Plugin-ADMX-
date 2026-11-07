package de.archer.admx.render.fixture;

import de.archer.admx.render.beam.BeamMode;
import de.archer.admx.render.fog.FogType;
import org.bukkit.Material;

/**
 * Definiert die verfügbaren Licht-, Laser- und Bühneneffekt-Gerätetypen in ADMX mit ihrem
 * jeweiligen Einsatzzweck, dem zugehörigen 3D-Modell und dem physikalischen Verhalten.
 */
public enum FixtureType {

    /**
     * 3-teiliger Moving Head (Base, Yoke, Head) mit volumetrischem 3D-Spotlight-Kegel.
     */
    MOVING_HEAD_SPOT(
            "Moving Head Spotlight",
            "3-teiliger Moving Head mit volumetrischem 3D-Lichtkegel (Pan/Tilt Sweep)",
            BeamMode.SPOT_CONE,
            Material.SEA_LANTERN,
            true,
            null
    ),

    /**
     * 3-teiliger Moving Head mit extrem scharfem, eng gebündeltem Nadelstrahl (Sharpie / Beam).
     */
    MOVING_HEAD_BEAM(
            "Moving Head Beam (Sharpie)",
            "Enger, messerscharfer Nadelstrahl mit konvexer Beam-Optik für Beam-Shows",
            BeamMode.LASER_NEEDLE,
            Material.END_ROD,
            true,
            null
    ),

    /**
     * 3-teiliger Moving Head als flächiger Washlight-Fluter mit 7-Linsen-Wabencluster.
     */
    MOVING_HEAD_WASH(
            "Moving Head Wash",
            "Weitwinkliger Fluter mit Waben-Linsen-Cluster für weiche Farb-Ausleuchtung",
            BeamMode.SPOT_CONE,
            Material.GLOWSTONE,
            true,
            null
    ),

    /**
     * Statisches Laser-Projektorgehäuse (Show-Laser) mit flachem Scanning-Laserfächer.
     */
    LASER_FAN_PROJECTOR(
            "Show-Laser (Scanning Fan)",
            "Statischer Club-Laserprojektor mit vertikal scannendem 2D-Laserfächer",
            BeamMode.LASER_FAN,
            Material.AMETHYST_CLUSTER,
            false,
            null
    ),

    /**
     * Statisches Laser-Projektorgehäuse (Beam-Laser) mit scharfem Nadelstrahl.
     */
    LASER_BEAM_PROJECTOR(
            "Show-Laser (Needle Beam)",
            "Statischer Laserprojektor mit scharfem, gebündeltem Nadelstrahl",
            BeamMode.LASER_NEEDLE,
            Material.POINTED_DRIPSTONE,
            false,
            null
    ),

    /**
     * 3-teiliger Moving Laser Head (Base, Yoke, Laser-Head) für bewegte 360°-Laserfächer.
     */
    MOVING_LASER_HEAD(
            "Moving Laser Head",
            "3-teiliger Schwenkkopf-Showlaser für dynamische Pan/Tilt-Laserprojektionen",
            BeamMode.LASER_FAN,
            Material.BEACON,
            true,
            null
    ),

    /**
     * Klassischer Publikums-Blinder für intensive Warmweiß-Fluten.
     */
    BLINDER_2LITE(
            "Audience Blinder (2-Lite)",
            "Klassischer 2-fach Warmweiß-Publikumsblinder für Konzert-Akzente",
            BeamMode.SPOT_CONE,
            Material.REDSTONE_LAMP,
            false,
            null
    ),

    /**
     * LED Strobe-Bar / Sunstrip für rasante Strobe-Gewitter und Farbwellen.
     */
    STROBE_BAR(
            "LED Strobe / Sunstrip",
            "Mehrsegment-LED-Leiste für High-Speed-Strobes und Lauflicht-Effekte",
            BeamMode.SPOT_CONE,
            Material.OCHRE_FROGLIGHT,
            false,
            null
    ),

    /**
     * Raumnebelmaschine (Stage Hazer): Füllt das berechnete Raumvolumen (max. 30 Blöcke)
     * mehrschichtig aus, bricht Bühnenlicht und erzeugt Sichtraub.
     */
    FOG_MACHINE_HAZER(
            "Raumnebelmaschine (Stage Hazer)",
            "Physikalischer Raumnebel (Dreiecks-Wellen + Lichtbrechung + Sichtraub bis 30m)",
            BeamMode.SPOT_CONE,
            Material.CAMPFIRE,
            false,
            FogType.ROOM_HAZE
    ),

    /**
     * Kaltnebelmaschine (Bodennebel / Cryo-Fogger): Erzeugt schweren, bodennahen Kaltnebel
     * als verbundene Dreiecks-Wellenoberfläche entlang des Raumbodens (max. 30 Blöcke).
     */
    FOG_MACHINE_LOW_FOG(
            "Kaltnebelmaschine (Bodennebel)",
            "Physikalischer Kaltnebel am Boden (Dreiecks-Wellen + Kanten-Partikel bis 30m)",
            BeamMode.SPOT_CONE,
            Material.SOUL_CAMPFIRE,
            false,
            FogType.LOW_FOG
    ),

    /**
     * DMX Bühnen-Flammenprojektor für vertikale Pyro-Flammenstöße.
     */
    STAGE_FLAME(
            "DMX Flammenprojektor (Stage Flame)",
            "Bühnen-Pyrogerät mit Sicherheitskäfig und vertikalen Flammenstößen",
            BeamMode.SPOT_CONE,
            Material.BLAZE_POWDER,
            false,
            null
    );

    private final String displayName;
    private final String description;
    private final BeamMode beamMode;
    private final Material iconMaterial;
    private final boolean movingHead;
    private final FogType fogType;

    FixtureType(
            String displayName,
            String description,
            BeamMode beamMode,
            Material iconMaterial,
            boolean movingHead,
            FogType fogType
    ) {
        this.displayName = displayName;
        this.description = description;
        this.beamMode = beamMode;
        this.iconMaterial = iconMaterial;
        this.movingHead = movingHead;
        this.fogType = fogType;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public BeamMode beamMode() {
        return beamMode;
    }

    public Material iconMaterial() {
        return iconMaterial;
    }

    public boolean isMovingHead() {
        return movingHead;
    }

    public boolean isLaser() {
        return this == LASER_FAN_PROJECTOR || this == LASER_BEAM_PROJECTOR || this == MOVING_LASER_HEAD;
    }

    public boolean isFogMachine() {
        return fogType != null;
    }

    public FogType fogType() {
        return fogType;
    }

    public boolean isFlameProjector() {
        return this == STAGE_FLAME;
    }
}
