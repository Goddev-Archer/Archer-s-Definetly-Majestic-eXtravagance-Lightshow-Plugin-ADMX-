package de.archer.admx.render.color;

import org.bukkit.Color;

/**
 * Perzeptiv gleichförmiger Farbraum Oklab als Java 25 Record (inkl. korrekter sRGB <-> Linear-RGB
 * Transferfunktion nach IEC 61966-2-1 und reinem Java HSB-Konverter ohne java.awt-Abhängigkeit).
 * Ermöglicht natürliche, lebendige Farbverläufe ohne die bei linearem sRGB
 * typischen Helligkeitseinbrüche und Schmutzfarben (z. B. Grau/Braun zwischen Rot und Grün).
 */
public record Oklab(float l, float a, float b, int alpha) {

    public Oklab {
        alpha = Math.clamp(alpha, 0, 255);
    }

    public Oklab(float l, float a, float b) {
        this(l, a, b, 255);
    }

    /**
     * Lineare Interpolation (lerp) im Oklab-Farbraum.
     *
     * @param other Zielfarbe
     * @param t     Fortschritt (0.0 bis 1.0)
     * @return Interpolierte Farbe im Oklab-Farbraum
     */
    public Oklab lerp(Oklab other, float t) {
        float clampedT = Math.clamp(t, 0.0f, 1.0f);
        return new Oklab(
                lerpFloat(this.l, other.l, clampedT),
                lerpFloat(this.a, other.a, clampedT),
                lerpFloat(this.b, other.b, clampedT),
                (int) Math.round(lerpFloat(this.alpha, other.alpha, clampedT))
        );
    }

    /**
     * Konvertiert diese Oklab-Farbe zurück in eine Bukkit-sRGB-Farbe mit Alpha-Kanal.
     */
    public Color toRGB() {
        double lVal = this.l;
        double aVal = this.a;
        double bVal = this.b;

        double lCbrt = lVal + 0.3963377774 * aVal + 0.2158037573 * bVal;
        double mCbrt = lVal - 0.1055613458 * aVal - 0.0638541728 * bVal;
        double sCbrt = lVal - 0.0894841775 * aVal - 1.2914855480 * bVal;

        double lCubed = lCbrt * lCbrt * lCbrt;
        double mCubed = mCbrt * mCbrt * mCbrt;
        double sCubed = sCbrt * sCbrt * sCbrt;

        double rLin = +4.0767416621 * lCubed - 3.3077115913 * mCubed + 0.2309699292 * sCubed;
        double gLin = -1.2684380046 * lCubed + 2.6097574011 * mCubed - 0.3413193965 * sCubed;
        double bLin = -0.0041960863 * lCubed - 0.7034186147 * mCubed + 1.7076147010 * sCubed;

        int red = Math.clamp((int) Math.round(linearToSrgb(rLin) * 255.0), 0, 255);
        int green = Math.clamp((int) Math.round(linearToSrgb(gLin) * 255.0), 0, 255);
        int blue = Math.clamp((int) Math.round(linearToSrgb(bLin) * 255.0), 0, 255);

        return Color.fromARGB(this.alpha, red, green, blue);
    }

    /**
     * Euklidischer Abstand zweier Farben im Oklab-Farbraum ($\Delta E$).
     * Entspricht der vom menschlichen Auge wahrgenommenen Farbdifferenz.
     */
    public float distanceTo(Oklab other) {
        float dl = this.l - other.l;
        float da = this.a - other.a;
        float db = this.b - other.b;
        return (float) Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * Skaliert die perzeptive Helligkeit $L$ mit einem Faktor (z. B. für Master-Dimmer).
     */
    public Oklab scaleBrightness(float factor) {
        return new Oklab(Math.clamp(this.l * factor, 0.0f, 1.0f), this.a, this.b, this.alpha);
    }

    /**
     * Erstellt eine Kopie mit neuem Alpha-Wert.
     */
    public Oklab withAlpha(int newAlpha) {
        return new Oklab(this.l, this.a, this.b, newAlpha);
    }

    /**
     * Konvertiert eine Bukkit-Farbe in den Oklab-Farbraum.
     */
    public static Oklab fromRGB(Color color) {
        return fromRGB(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    /**
     * Konvertiert 8-Bit-sRGB-Werte (0-255) über Linear-RGB in Oklab.
     */
    public static Oklab fromRGB(int red, int green, int blue, int alpha) {
        double r = srgbToLinear(Math.clamp(red, 0, 255) / 255.0);
        double g = srgbToLinear(Math.clamp(green, 0, 255) / 255.0);
        double b = srgbToLinear(Math.clamp(blue, 0, 255) / 255.0);

        double lPart = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
        double mPart = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
        double sPart = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

        double lCbrt = Math.cbrt(lPart);
        double mCbrt = Math.cbrt(mPart);
        double sCbrt = Math.cbrt(sPart);

        float l = (float) (0.2104542553 * lCbrt + 0.7936177850 * mCbrt - 0.0040720468 * sCbrt);
        float a = (float) (1.9779984951 * lCbrt - 2.4285922050 * mCbrt + 0.4505937099 * sCbrt);
        float bVal = (float) (0.0259040371 * lCbrt + 0.7827717662 * mCbrt - 0.8086757660 * sCbrt);

        return new Oklab(l, a, bVal, alpha);
    }

    /**
     * Reiner Java HSB -> 24-Bit-RGB Konverter ohne Abhängigkeit auf {@code java.awt.Color}.
     *
     * @param hue        Farbton im Bereich [0.0 .. 1.0] (wird zyklisch gewrappt)
     * @param saturation Sättigung [0.0 .. 1.0]
     * @param brightness Helligkeit [0.0 .. 1.0]
     * @return 24-Bit RGB Integer (0xRRGGBB)
     */
    public static int hsbToRgbInt(float hue, float saturation, float brightness) {
        float s = Math.clamp(saturation, 0.0f, 1.0f);
        float v = Math.clamp(brightness, 0.0f, 1.0f);
        int r = 0, g = 0, b = 0;
        if (s == 0.0f) {
            r = g = b = Math.round(v * 255.0f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            int sector = (int) Math.floor(h);
            float f = h - sector;
            float p = v * (1.0f - s);
            float q = v * (1.0f - s * f);
            float t = v * (1.0f - (s * (1.0f - f)));
            switch (sector) {
                case 0 -> {
                    r = Math.round(v * 255.0f);
                    g = Math.round(t * 255.0f);
                    b = Math.round(p * 255.0f);
                }
                case 1 -> {
                    r = Math.round(q * 255.0f);
                    g = Math.round(v * 255.0f);
                    b = Math.round(p * 255.0f);
                }
                case 2 -> {
                    r = Math.round(p * 255.0f);
                    g = Math.round(v * 255.0f);
                    b = Math.round(t * 255.0f);
                }
                case 3 -> {
                    r = Math.round(p * 255.0f);
                    g = Math.round(q * 255.0f);
                    b = Math.round(v * 255.0f);
                }
                case 4 -> {
                    r = Math.round(t * 255.0f);
                    g = Math.round(p * 255.0f);
                    b = Math.round(v * 255.0f);
                }
                default -> {
                    r = Math.round(v * 255.0f);
                    g = Math.round(p * 255.0f);
                    b = Math.round(q * 255.0f);
                }
            }
        }
        return ((Math.clamp(r, 0, 255) & 0xFF) << 16)
                | ((Math.clamp(g, 0, 255) & 0xFF) << 8)
                | (Math.clamp(b, 0, 255) & 0xFF);
    }

    /**
     * Erstellt eine Bukkit-Color direkt aus HSB-Werten ohne java.awt.
     */
    public static Color hsbToBukkitColor(float hueDegrees, float saturation, float brightness) {
        return Color.fromRGB(hsbToRgbInt(hueDegrees / 360.0f, saturation, brightness));
    }

    /**
     * Erstellt eine Oklab-Farbe direkt aus HSB-Werten ohne java.awt.
     */
    public static Oklab fromHSB(float hueDegrees, float saturation, float brightness) {
        return fromRGB(hsbToBukkitColor(hueDegrees, saturation, brightness));
    }

    private static double srgbToLinear(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double c) {
        double clamped = Math.clamp(c, 0.0, 1.0);
        if (clamped <= 0.0031308) {
            return 12.92 * clamped;
        }
        return 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
    }

    private static float lerpFloat(float start, float end, float t) {
        return start + (end - start) * t;
    }
}
