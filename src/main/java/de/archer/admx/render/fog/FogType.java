package de.archer.admx.render.fog;

import de.archer.admx.render.color.Oklab;
import org.bukkit.Color;

/**
 * Definiert die verschiedenen physikalischen Nebel-Arten für ADMX-Nebelmaschinen:
 * - {@link #LOW_FOG}: Schwerer Kaltnebel (Bodennebel / Dry-Ice / Ultrasonic Chiller), sinkt zu Boden,
 *   fließt über Kanten hinab und bildet eine dichte, wogende Bodenschicht.
 * - {@link #ROOM_HAZE}: Atmosphärischer Raumnebel (Stage Hazer / Verdampfer-Nebel), breitet sich
 *   dreidimensional im gesamten Raumvolumen aus, bricht Bühnenlicht (Tyndall-Effekt) und erzeugt
 *   Sichtraub bei dichter Konzentration.
 */
public enum FogType {

    /**
     * Kaltnebel (Bodennebel): Schwerer als Luft, bleibt bodennah (ca. 0.35m – 1.15m über dem Boden),
     * hohe optische Dichte, kühler Eisweiß-Farbton.
     */
    LOW_FOG(
            "Kaltnebel (Bodennebel)",
            1.15f,
            0.42f,
            0.15f,
            0.55f,
            0.34f,
            0.62f,
            1,
            Oklab.fromRGB(Color.fromRGB(235, 246, 255))
    ),

    /**
     * Raumnebel (Bühnen-Haze / Atmosphärennebel): Füllt das Raumvolumen mehrschichtig bis zur Decke
     * (bzw. bis ca. 5.5m Höhe), bricht Scheinwerferstrahlen intensiv und erzeugt homogenen Sichtraub.
     */
    ROOM_HAZE(
            "Raumnebel (Stage Haze)",
            5.50f,
            1.30f,
            0.26f,
            0.38f,
            0.16f,
            0.42f,
            2,
            Oklab.fromRGB(Color.fromRGB(224, 230, 240))
    );

    /**
     * Maximale Ausbreitungsdistanz des Nebels vom Ausstoßpunkt (Regel: max. 30 Blöcke).
     */
    public static final float MAX_REACH_BLOCKS = 30.0f;

    private final String displayName;
    private final float maxVerticalSpread;
    private final float baseLayerHeight;
    private final float waveAmplitude;
    private final float waveFrequency;
    private final float surfaceBaseAlpha;
    private final float maxEyeVisorAlpha;
    private final int waveLayerCount;
    private final Oklab baseColor;

    FogType(
            String displayName,
            float maxVerticalSpread,
            float baseLayerHeight,
            float waveAmplitude,
            float waveFrequency,
            float surfaceBaseAlpha,
            float maxEyeVisorAlpha,
            int waveLayerCount,
            Oklab baseColor
    ) {
        this.displayName = displayName;
        this.maxVerticalSpread = maxVerticalSpread;
        this.baseLayerHeight = baseLayerHeight;
        this.waveAmplitude = waveAmplitude;
        this.waveFrequency = waveFrequency;
        this.surfaceBaseAlpha = surfaceBaseAlpha;
        this.maxEyeVisorAlpha = maxEyeVisorAlpha;
        this.waveLayerCount = waveLayerCount;
        this.baseColor = baseColor;
    }

    public String displayName() {
        return displayName;
    }

    public float maxVerticalSpread() {
        return maxVerticalSpread;
    }

    public float baseLayerHeight() {
        return baseLayerHeight;
    }

    public float waveAmplitude() {
        return waveAmplitude;
    }

    public float waveFrequency() {
        return waveFrequency;
    }

    public float surfaceBaseAlpha() {
        return surfaceBaseAlpha;
    }

    public float maxEyeVisorAlpha() {
        return maxEyeVisorAlpha;
    }

    public int waveLayerCount() {
        return waveLayerCount;
    }

    public Oklab baseColor() {
        return baseColor;
    }
}
