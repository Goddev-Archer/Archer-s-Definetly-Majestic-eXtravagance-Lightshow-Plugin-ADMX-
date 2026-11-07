package de.archer.admx.audio;

/**
 * Digitales IIR-Biquad-Bandpassfilter (35 Hz - 140 Hz) zur Isolation von Kick-Drums und 808-Subbässen.
 * <p>
 * Filtert hohe Frequenzen (Hi-Hats, Vocals, Leads) effizient heraus, damit die Onset-Erkennung
 * unempfindlich gegenüber Melodien und Sprache bleibt.
 */
public final class SubBassBandpassFilter {

    private final double b0, b1, b2;
    private final double a1, a2;

    private double x1 = 0.0, x2 = 0.0;
    private double y1 = 0.0, y2 = 0.0;

    public SubBassBandpassFilter(double sampleRateHz, double lowCutoffHz, double highCutoffHz) {
        double f0 = Math.sqrt(lowCutoffHz * highCutoffHz);
        double q = f0 / Math.max(10.0, highCutoffHz - lowCutoffHz);

        double w0 = 2.0 * Math.PI * f0 / sampleRateHz;
        double alpha = Math.sin(w0) / (2.0 * q);

        double a0 = 1.0 + alpha;
        this.b0 = (alpha) / a0;
        this.b1 = 0.0;
        this.b2 = (-alpha) / a0;
        this.a1 = (-2.0 * Math.cos(w0)) / a0;
        this.a2 = (1.0 - alpha) / a0;
    }

    public SubBassBandpassFilter(double sampleRateHz) {
        this(sampleRateHz, 35.0, 140.0);
    }

    public SubBassBandpassFilter() {
        this(44100.0, 35.0, 140.0);
    }

    /**
     * Filtert ein einzelnes Sample x in den Sub-Bass-Bereich y.
     */
    public double processSample(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

        // Anti-Denormal Schutz
        if (Math.abs(y) < 1e-15) {
            y = 0.0;
        }

        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;

        return y;
    }

    /**
     * Filtert ein Sample-Array in-place.
     */
    public void processBuffer(double[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            buffer[offset + i] = processSample(buffer[offset + i]);
        }
    }

    public void reset() {
        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
    }
}
